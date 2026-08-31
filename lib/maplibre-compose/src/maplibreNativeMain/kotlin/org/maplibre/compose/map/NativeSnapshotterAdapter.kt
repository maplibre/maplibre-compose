package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.util.toCameraOptions
import org.maplibre.compose.util.toImageBitmap
import org.maplibre.nativeffi.camera.EdgeInsets
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.map.MapMode
import org.maplibre.nativeffi.render.NativeBuffer
import org.maplibre.nativeffi.render.RenderResult
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.runtime.RuntimeEvent
import org.maplibre.nativeffi.runtime.RuntimeEventMask
import org.maplibre.nativeffi.runtime.RuntimeEventType

private val SNAPSHOT_EVENTS =
  RuntimeEventMask.MAP_STYLE_LOADED +
    RuntimeEventMask.MAP_LOADING_FAILED +
    RuntimeEventMask.MAP_STILL_IMAGE_FINISHED +
    RuntimeEventMask.MAP_STILL_IMAGE_FAILED +
    RuntimeEventMask.MAP_RENDER_ERROR +
    RuntimeEventMask.MAP_RENDER_UPDATE_AVAILABLE

internal class NativeSnapshotterAdapterFactory(private val options: MlnFfiRuntimeOptions) :
  SnapshotterAdapterFactory {
  override fun create(): SnapshotterAdapter = NativeSnapshotterAdapter(options)
}

/** One private map, offscreen render session, and retained reconciler for a native snapshotter. */
private class NativeSnapshotterAdapter(private val options: MlnFfiRuntimeOptions) :
  SnapshotterAdapter {
  @Volatile private var open = true
  @Volatile private var engine: NativeSnapshotEngine? = null
  @Volatile private var styleBinding: MlnFfiStyleBinding? = null
  @Volatile private var loadedBaseStyleRevision: Long? = null
  @Volatile private var currentDensity = 1f
  @Volatile private var terminalOperation: CompletableDeferred<Result<Unit>>? = null
  @Volatile private var stillImageOperation = false
  @Volatile private var renderedFrame = false
  private val reconciler = StyleReconciler()

  override suspend fun prepare(
    baseStyle: BaseStyle,
    baseStyleRevision: Long,
    request: MapSnapshotRequest,
  ): StyleBinding = runNativeRequest {
    ensureEngine(request)
    currentDensity = request.density
    configureRequest(request)
    val current = styleBinding
    if (baseStyleRevision == loadedBaseStyleRevision && current?.isLoaded == true) {
      return@runNativeRequest current
    }

    current?.invalidate()
    styleBinding = null
    loadedBaseStyleRevision = null
    val loading = CompletableDeferred<Result<Unit>>()
    terminalOperation = loading
    postToMap { map ->
      when (baseStyle) {
        is BaseStyle.Uri -> map.setStyleUrl(baseStyle.uri)
        is BaseStyle.Json -> map.setStyleJson(baseStyle.json.encodeToByteArray())
      }
    }
    val loadResult = loading.await()
    if (terminalOperation === loading) terminalOperation = null
    loadResult.getOrThrow()
    loadedBaseStyleRevision = baseStyleRevision
    checkNotNull(styleBinding) { "MapLibre reported a loaded style without a binding" }
  }

  override suspend fun capture(
    request: MapSnapshotRequest,
    revision: DesiredStyleRevision,
  ): ImageBitmap = runNativeRequest {
    val binding = checkNotNull(styleBinding) { "A snapshot style has not loaded" }
    reconciler.apply(binding, revision)
    configureRequest(request)
    val rendering = CompletableDeferred<Result<Unit>>()
    terminalOperation = rendering
    stillImageOperation = true
    renderedFrame = false
    try {
      postToMap { map -> map.requestStillImage() }
      driveStillImage(rendering)
      if (terminalOperation === rendering) terminalOperation = null
      readImage(request)
    } finally {
      stillImageOperation = false
    }
  }

  override suspend fun cancelActiveCapture(): SnapshotterEngineDisposition {
    val operation = terminalOperation ?: return SnapshotterEngineDisposition.RETAINED
    if (stillImageOperation) driveStillImage(operation) else operation.await()
    return SnapshotterEngineDisposition.RETAINED
  }

  override suspend fun close() {
    if (!open) return
    open = false
    val failures = mutableListOf<Throwable>()
    runCatching { styleBinding?.invalidate() }.exceptionOrNull()?.let(failures::add)
    styleBinding = null
    loadedBaseStyleRevision = null
    releaseEngine(failures)
    throwCleanupFailures(failures)
  }

  private fun releaseEngine(failures: MutableList<Throwable>) {
    val current = engine ?: return
    engine = null
    runCatching { current.loop.close() }.exceptionOrNull()?.let(failures::add)
  }

  private fun throwCleanupFailures(failures: List<Throwable>) {
    if (failures.isNotEmpty()) {
      throw AggregateCleanupException(
        "Native snapshotter cleanup failed in ${failures.size} resource(s)",
        failures,
      )
    }
  }

  private suspend fun ensureEngine(request: MapSnapshotRequest) {
    val extent = request.extent()
    if (engine?.let { it.scaleFactor == extent.scaleFactor && it.loop.failure == null } == true) {
      return
    }
    if (engine != null) {
      val failures = mutableListOf<Throwable>()
      runCatching { styleBinding?.invalidate() }.exceptionOrNull()?.let(failures::add)
      styleBinding = null
      loadedBaseStyleRevision = null
      releaseEngine(failures)
      throwCleanupFailures(failures)
    }
    val created = CompletableDeferred<Result<Unit>>()
    val resources = NativeSnapshotRenderResources(extent, options)
    lateinit var candidate: NativeSnapshotEngine
    val candidateLoop =
      MlnFfiMapRuntimeLoop(
        extent = extent,
        cacheFile = options.cacheFile,
        getLogger = { options.logger },
        resourceProviderFactory = options.resourceProviderFactory,
        onMapCreated = resources::attach,
        onMapPublished = { created.complete(Result.success(Unit)) },
        onMapClosing = { resources.close() },
        onEvent = { event -> handleEvent(candidate, event) },
        onEventsDrained = {},
        requestFrame = {},
        mapEventMask = SNAPSHOT_EVENTS,
        mapMode = MapMode.STATIC,
        onFailure = { error ->
          val failure = Result.failure<Unit>(error)
          created.complete(failure)
          if (engine === candidate) terminalOperation?.complete(failure)
        },
      )
    candidate = NativeSnapshotEngine(candidateLoop, resources, extent.scaleFactor)
    engine = candidate
    terminalOperation = created
    candidateLoop.start()
    val creationResult = created.await()
    if (terminalOperation === created) terminalOperation = null
    try {
      creationResult.getOrThrow()
    } catch (error: Throwable) {
      if (engine === candidate) engine = null
      runCatching { candidateLoop.close() }.exceptionOrNull()?.let(error::addSuppressed)
      throw error
    }
  }

  private suspend fun configureRequest(request: MapSnapshotRequest) {
    val currentEngine = checkNotNull(engine)
    val currentLoop = currentEngine.loop
    val extent = request.extent()
    val resized = CompletableDeferred<Result<Unit>>()
    terminalOperation = resized
    try {
      checkNotNull(
        currentLoop.call(
          action = { map ->
            currentEngine.resources.withSession { session ->
              check(currentEngine.scaleFactor == extent.scaleFactor) {
                "The snapshot engine scale does not match the capture request"
              }
              session.resize(extent.width, extent.height, extent.scaleFactor)
            }
            map.jumpTo(request.cameraPosition.toCameraOptions(EdgeInsets.ZERO))
          }
        )
      ) {
        "The snapshotter engine map stopped during request configuration"
      }
      if (
        !currentLoop.postEventDrainBarrier(
          { resized.complete(Result.success(Unit)) },
          {
            resized.complete(Result.failure(currentLoop.failure ?: MapSnapshotterClosedException()))
          },
        )
      ) {
        resized.complete(Result.failure(currentLoop.failure ?: MapSnapshotterClosedException()))
      }
    } catch (error: Throwable) {
      resized.complete(Result.failure(error))
    }
    val resizeResult = resized.await()
    if (terminalOperation === resized) terminalOperation = null
    resizeResult.getOrThrow()
  }

  private suspend fun <T> runNativeRequest(action: suspend () -> T): T =
    try {
      action()
    } finally {
      val operation = terminalOperation
      if (operation != null) {
        withContext(NonCancellable) { operation.await() }
        if (terminalOperation === operation) terminalOperation = null
      }
    }

  private fun handleEvent(source: NativeSnapshotEngine, event: RuntimeEvent) {
    if (engine !== source) return
    when (event.type) {
      RuntimeEventType.MAP_STYLE_LOADED -> {
        val binding = createStyleBinding(source)
        styleBinding?.invalidate()
        styleBinding = binding
        terminalOperation?.complete(Result.success(Unit))
      }
      RuntimeEventType.MAP_LOADING_FAILED,
      RuntimeEventType.MAP_STILL_IMAGE_FAILED,
      RuntimeEventType.MAP_RENDER_ERROR -> {
        val message = event.message.ifBlank { "MapLibre snapshot capture failed" }
        terminalOperation?.complete(Result.failure(IllegalStateException(message)))
      }
      RuntimeEventType.MAP_STILL_IMAGE_FINISHED -> terminalOperation?.complete(Result.success(Unit))
      else -> Unit
    }
  }

  private fun createStyleBinding(source: NativeSnapshotEngine): MlnFfiStyleBinding =
    MlnFfiStyleBinding(
      loggerProvider = { options.logger },
      sessionOpen = { open },
      accessMap = { action -> source.loop.call(action = action) != null },
      accessRenderSession = { action ->
        source.loop.call(action = { _ -> source.resources.withSession(action) }) != null
      },
      getScale = { currentDensity },
      requestRepaint = {},
    )

  private fun readImage(request: MapSnapshotRequest): ImageBitmap {
    val expected = request.extent()
    val currentEngine = checkNotNull(engine)
    val rgba =
      currentEngine.loop.call(
        action = { _ ->
          currentEngine.resources.withSession { session ->
            val info = session.textureImageInfo()
            NativeBuffer.allocate(info.byteLength).use { buffer ->
              val copied = session.readPremultipliedRgba8(buffer)
              Triple(copied, buffer.toByteArray(), request.outputOptions.transparent)
            }
          }
        }
      ) ?: error("The snapshotter engine map is closed")
    val (info, bytes, transparent) = rgba
    check(info.width == expected.physicalWidth && info.height == expected.physicalHeight) {
      "Snapshot readback was ${info.width}x${info.height}, expected " +
        "${expected.physicalWidth}x${expected.physicalHeight}"
    }
    val pixels = IntArray(info.width * info.height)
    for (y in 0 until info.height) {
      for (x in 0 until info.width) {
        val source = y * info.stride + x * 4
        val alpha = bytes[source + 3].toInt() and 0xff
        val red = unpremultiply(bytes[source].toInt() and 0xff, alpha)
        val green = unpremultiply(bytes[source + 1].toInt() and 0xff, alpha)
        val blue = unpremultiply(bytes[source + 2].toInt() and 0xff, alpha)
        pixels[y * info.width + x] =
          if (transparent) {
            (alpha shl 24) or (red shl 16) or (green shl 8) or blue
          } else {
            val inverse = 255 - alpha
            (0xff shl 24) or
              ((red * alpha / 255 + inverse) shl 16) or
              ((green * alpha / 255 + inverse) shl 8) or
              (blue * alpha / 255 + inverse)
          }
      }
    }
    return pixels.toImageBitmap(info.width, info.height)
  }

  private fun postToMap(action: (MapHandle) -> Unit) {
    val currentLoop = checkNotNull(engine).loop
    if (
      !currentLoop.post(
        action = { map ->
          runCatching { action(map) }.onFailure { terminalOperation?.complete(Result.failure(it)) }
        },
        abandon = {
          terminalOperation?.complete(
            Result.failure(currentLoop.failure ?: MapSnapshotterClosedException())
          )
        },
      )
    ) {
      terminalOperation?.complete(
        Result.failure(currentLoop.failure ?: MapSnapshotterClosedException())
      )
    }
  }

  private suspend fun driveStillImage(operation: CompletableDeferred<Result<Unit>>) {
    val currentEngine = checkNotNull(engine)
    while (!operation.isCompleted || !renderedFrame) {
      if (operation.isCompleted) operation.await().getOrThrow()
      val update =
        currentEngine.loop.call(
          action = { _ -> currentEngine.resources.withSession { it.renderUpdate() } }
        )
          ?: throw MapSnapshotterClosedException().also { error ->
            operation.complete(Result.failure(error))
          }
      if (update.result == RenderResult.RENDERED) renderedFrame = true
      if (!operation.isCompleted || !renderedFrame) delay(2)
    }
    operation.await().getOrThrow()
  }

  private fun unpremultiply(channel: Int, alpha: Int): Int =
    if (alpha == 0) 0 else (channel * 255 + alpha / 2) / alpha

  private fun MapSnapshotRequest.extent(): MapExtent =
    MapExtent.fromLogical(width, height, density.toDouble())
}

/** A loop and the render resources owned exclusively by that loop's thread. */
private class NativeSnapshotEngine(
  val loop: MlnFfiMapRuntimeLoop,
  val resources: NativeSnapshotRenderResources,
  val scaleFactor: Double,
)

/** Offscreen resources that are attached, accessed, and closed only on one map's owner thread. */
private class NativeSnapshotRenderResources(
  private val extent: MapExtent,
  private val options: MlnFfiRuntimeOptions,
) {
  private var target: NativeSnapshotRenderTarget? = null
  private var session: RenderSessionHandle? = null

  fun attach(map: MapHandle) {
    var createdTarget: NativeSnapshotRenderTarget? = null
    try {
      createdTarget = NativeSnapshotRenderTarget.create(loadRuntimeBackends(options.logger))
      val createdSession = createdTarget.attach(map, extent)
      target = createdTarget
      session = createdSession
    } catch (error: Throwable) {
      createdTarget?.let { target ->
        runCatching { target.close() }.exceptionOrNull()?.let(error::addSuppressed)
      }
      throw error
    }
  }

  fun <T> withSession(action: (RenderSessionHandle) -> T): T =
    checkNotNull(target).withAccess { action(checkNotNull(session)) }

  fun close() {
    val failures = mutableListOf<Throwable>()
    val currentTarget = target
    if (currentTarget == null) {
      val currentSession = session
      session = null
      runCatching { currentSession?.close() }.exceptionOrNull()?.let(failures::add)
      throwCleanupFailures(failures)
      return
    }
    try {
      currentTarget.withAccess {
        val currentSession = session
        session = null
        target = null
        runCatching { currentSession?.close() }.exceptionOrNull()?.let(failures::add)
        runCatching { currentTarget.close() }.exceptionOrNull()?.let(failures::add)
      }
    } catch (error: Throwable) {
      failures += error
      if (target === currentTarget) {
        val currentSession = session
        session = null
        target = null
        runCatching { currentSession?.close() }.exceptionOrNull()?.let(failures::add)
        runCatching { currentTarget.close() }.exceptionOrNull()?.let(failures::add)
      }
    }
    throwCleanupFailures(failures)
  }

  private fun throwCleanupFailures(failures: List<Throwable>) {
    if (failures.isNotEmpty()) {
      throw AggregateCleanupException(
        "Native snapshotter cleanup failed in ${failures.size} resource(s)",
        failures,
      )
    }
  }
}

internal expect class NativeSnapshotRenderTarget : AutoCloseable {
  fun attach(map: MapHandle, extent: MapExtent): RenderSessionHandle

  fun <T> withAccess(action: () -> T): T

  override fun close()

  companion object {
    fun create(backends: Set<MapRenderBackend>): NativeSnapshotRenderTarget
  }
}
