package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.util.metersPerDpAtLatitude
import org.maplibre.compose.util.toCameraOptions
import org.maplibre.compose.util.toImageBitmap
import org.maplibre.nativeffi.camera.EdgeInsets
import org.maplibre.nativeffi.error.MaplibreException
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

internal class NativeSnapshotterAdapterFactory(
  private val options: MlnFfiRuntimeOptions,
  private val resourceConfig: MapResourceConfig,
  private val runtimeBackends: () -> Set<MapRenderBackend> = {
    loadRuntimeBackends(options.logger)
  },
) : SnapshotterAdapterFactory {
  override fun create(): SnapshotterAdapter {
    val backends = runtimeBackends()
    val targetPlan =
      NativeSnapshotRenderTarget.select(backends)
        ?: throw UnsupportedOperationException(
          "No compatible offscreen snapshot backend is available from ${backends.joinToString()}"
        )
    return NativeSnapshotterAdapter(options, resourceConfig, targetPlan)
  }
}

/** One private map, offscreen render session, and retained reconciler for a native snapshotter. */
private class NativeSnapshotterAdapter(
  private val options: MlnFfiRuntimeOptions,
  private val resourceConfig: MapResourceConfig,
  private val targetPlan: NativeSnapshotRenderTargetPlan,
) : SnapshotterAdapter {
  @Volatile private var open = true
  @Volatile private var engine: NativeSnapshotEngine? = null
  @Volatile private var styleBinding: MlnFfiStyleBinding? = null
  @Volatile private var loadedBaseStyleRevision: Long? = null
  @Volatile private var currentDensity = 1f
  @Volatile private var terminalOperation: NativeSnapshotOperation? = null
  @Volatile private var stillImageOperation: NativeSnapshotOperation? = null
  @Volatile private var renderedFrame = false
  private val reconciler = StyleReconciler()

  override suspend fun prepare(
    baseStyle: BaseStyle,
    baseStyleRevision: Long,
    request: MapSnapshotRequest,
  ): SnapshotPreparation = runNativeRequest {
    ensureEngine(request)
    currentDensity = request.density
    val viewport = configureRequest(request)
    val current = styleBinding
    if (baseStyleRevision == loadedBaseStyleRevision && current?.isLoaded == true) {
      return@runNativeRequest SnapshotPreparation(current, viewport)
    }

    current?.invalidate()
    styleBinding = null
    loadedBaseStyleRevision = null
    val loading = NativeSnapshotOperation(NativeSnapshotOperation.Kind.STYLE)
    terminalOperation = loading
    postStyleToMap(loading, baseStyle)
    val loadResult = loading.completion.await()
    if (terminalOperation === loading) terminalOperation = null
    loadResult.getOrThrow()
    loadedBaseStyleRevision = baseStyleRevision
    SnapshotPreparation(
      binding = checkNotNull(styleBinding) { "MapLibre reported a loaded style without a binding" },
      viewport = viewport,
    )
  }

  override suspend fun capture(
    request: MapSnapshotRequest,
    revision: DesiredStyleRevision,
  ): ImageBitmap = runNativeRequest {
    val binding = checkNotNull(styleBinding) { "A snapshot style has not loaded" }
    reconciler.apply(binding, revision)
    configureRequest(request)
    val rendering = NativeSnapshotOperation(NativeSnapshotOperation.Kind.STILL_IMAGE)
    stillImageOperation = rendering
    terminalOperation = rendering
    renderedFrame = false
    try {
      postToMap(rendering) { map -> map.requestStillImage() }
      driveStillImage(rendering.completion)
      if (terminalOperation === rendering) terminalOperation = null
      readImage(request)
    } finally {
      if (
        (currentCoroutineContext().isActive ||
          (rendering.completion.isCompleted && renderedFrame)) && stillImageOperation === rendering
      ) {
        stillImageOperation = null
      }
    }
  }

  override suspend fun cancelActiveCapture(): SnapshotterEngineDisposition {
    val stillImage = stillImageOperation
    if (stillImage != null) {
      try {
        driveStillImage(stillImage.completion)
      } finally {
        if (stillImageOperation === stillImage) stillImageOperation = null
      }
    } else {
      terminalOperation?.completion?.await()
    }
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
    failures.cleanupResult("Native snapshotter").getOrThrow()
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
    val created = NativeSnapshotOperation(NativeSnapshotOperation.Kind.ENGINE_CREATION)
    val resources = NativeSnapshotRenderResources(extent, targetPlan)
    lateinit var candidate: NativeSnapshotEngine
    val candidateLoop =
      MlnFfiMapRuntimeLoop(
        extent = extent,
        cacheFile = options.cacheFile,
        getLogger = { options.logger },
        resourceProviderFactory = options.resourceProviderFactory,
        resourceConfig = resourceConfig,
        onMapCreated = resources::attach,
        onMapPublished = { created.completion.complete(Result.success(Unit)) },
        onMapClosing = { resources.close() },
        onEvent = { event -> handleEvent(candidate, event) },
        onEventsDrained = {},
        requestFrame = {},
        mapEventMask = SNAPSHOT_EVENTS,
        mapMode = MapMode.STATIC,
        onFailure = { error ->
          val failure = Result.failure<Unit>(error)
          created.completion.complete(failure)
          if (engine === candidate) terminalOperation?.completion?.complete(failure)
        },
      )
    candidate = NativeSnapshotEngine(candidateLoop, resources, extent.scaleFactor)
    engine = candidate
    terminalOperation = created
    candidateLoop.start()
    val creationResult = created.completion.await()
    if (terminalOperation === created) terminalOperation = null
    try {
      creationResult.getOrThrow()
    } catch (error: Throwable) {
      if (engine === candidate) engine = null
      runCatching { candidateLoop.close() }.exceptionOrNull()?.let(error::addSuppressed)
      throw error
    }
  }

  private suspend fun configureRequest(request: MapSnapshotRequest): Viewport {
    val currentEngine = checkNotNull(engine)
    val currentLoop = currentEngine.loop
    val extent = request.extent()
    val resized = NativeSnapshotOperation(NativeSnapshotOperation.Kind.RESIZE)
    terminalOperation = resized
    var geometry: MapViewportGeometry? = null
    try {
      geometry =
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
              // Read on the same loop call that applied the size and camera, so the viewport
              // describes the transform this capture renders.
              map.readViewportGeometry()
            }
          )
        ) {
          "The snapshotter engine map stopped during request configuration"
        }
      if (
        !currentLoop.postEventDrainBarrier(
          { resized.completion.complete(Result.success(Unit)) },
          {
            resized.completion.complete(
              Result.failure(currentLoop.failure ?: snapshotterClosedCancellation())
            )
          },
        )
      ) {
        resized.completion.complete(
          Result.failure(currentLoop.failure ?: snapshotterClosedCancellation())
        )
      }
    } catch (error: Throwable) {
      resized.completion.complete(Result.failure(error))
    }
    val resizeResult = resized.completion.await()
    if (terminalOperation === resized) terminalOperation = null
    resizeResult.getOrThrow()
    val applied = checkNotNull(geometry)
    check(
      applied.size.width.value.toInt() == extent.width &&
        applied.size.height.value.toInt() == extent.height
    ) {
      "The snapshot map reported a ${applied.size} viewport, expected " +
        "${extent.width}x${extent.height} logical pixels"
    }
    return Viewport(
      size = applied.size,
      visibleBoundingBox = applied.boundingBox,
      visibleRegion = applied.visibleRegion,
      metersPerDpAtTarget =
        metersPerDpAtLatitude(applied.camera.zoom, applied.camera.target.latitude),
    )
  }

  private suspend fun <T> runNativeRequest(action: suspend () -> T): T =
    try {
      action()
    } finally {
      val operation = terminalOperation
      if (operation != null) {
        withContext(NonCancellable) { operation.completion.await() }
        if (terminalOperation === operation) terminalOperation = null
      }
    }

  private fun handleEvent(source: NativeSnapshotEngine, event: RuntimeEvent) {
    if (engine !== source) return
    val operation = terminalOperation
    when (event.type) {
      RuntimeEventType.MAP_STYLE_LOADED -> {
        if (operation?.kind != NativeSnapshotOperation.Kind.STYLE) return
        val binding = createStyleBinding(source)
        styleBinding?.invalidate()
        styleBinding = binding
        operation.completion.complete(Result.success(Unit))
      }
      RuntimeEventType.MAP_LOADING_FAILED -> {
        if (operation?.kind != NativeSnapshotOperation.Kind.STYLE) return
        val message = event.message.ifBlank { "MapLibre snapshot capture failed" }
        operation.completion.complete(Result.failure(IllegalStateException(message)))
      }
      RuntimeEventType.MAP_STILL_IMAGE_FAILED -> {
        if (operation?.kind != NativeSnapshotOperation.Kind.STILL_IMAGE) return
        val message = event.message.ifBlank { "MapLibre snapshot capture failed" }
        operation.completion.complete(Result.failure(IllegalStateException(message)))
      }
      RuntimeEventType.MAP_RENDER_ERROR -> {
        if (operation?.kind != NativeSnapshotOperation.Kind.STILL_IMAGE) return
        val message = event.message.ifBlank { "MapLibre snapshot capture failed" }
        operation.completion.complete(Result.failure(IllegalStateException(message)))
      }
      RuntimeEventType.MAP_STILL_IMAGE_FINISHED -> {
        if (operation?.kind == NativeSnapshotOperation.Kind.STILL_IMAGE) {
          operation.completion.complete(Result.success(Unit))
        }
      }
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

  private fun postStyleToMap(operation: NativeSnapshotOperation, baseStyle: BaseStyle) {
    postToMap(operation) { map ->
      try {
        when (baseStyle) {
          is BaseStyle.Uri -> map.setStyleUrl(baseStyle.uri)
          is BaseStyle.Json -> map.setStyleJson(baseStyle.json.encodeToByteArray())
        }
      } catch (_: MaplibreException) {
        // A rejected inline style also queues MAP_LOADING_FAILED. That event owns completion so it
        // is drained before the FIFO worker can expose the next operation to snapshot events.
      }
    }
  }

  private fun postToMap(operation: NativeSnapshotOperation, action: (MapHandle) -> Unit) {
    val currentLoop = checkNotNull(engine).loop
    if (
      !currentLoop.post(
        action = { map ->
          runCatching { action(map) }
            .onFailure { operation.completion.complete(Result.failure(it)) }
        },
        abandon = {
          operation.completion.complete(
            Result.failure(currentLoop.failure ?: snapshotterClosedCancellation())
          )
        },
      )
    ) {
      operation.completion.complete(
        Result.failure(currentLoop.failure ?: snapshotterClosedCancellation())
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
          ?: throw snapshotterClosedCancellation().also { error ->
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

  private class NativeSnapshotOperation(val kind: Kind) {
    val completion = CompletableDeferred<Result<Unit>>()

    enum class Kind {
      ENGINE_CREATION,
      STYLE,
      RESIZE,
      STILL_IMAGE,
    }
  }
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
  private val targetPlan: NativeSnapshotRenderTargetPlan,
) {
  private var target: NativeSnapshotRenderTarget? = null
  private var session: RenderSessionHandle? = null

  fun attach(map: MapHandle) {
    var createdTarget: NativeSnapshotRenderTarget? = null
    try {
      createdTarget = targetPlan.create()
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
    failures.cleanupResult("Native snapshotter").getOrThrow()
  }
}

internal fun interface NativeSnapshotRenderTargetPlan {
  fun create(): NativeSnapshotRenderTarget
}

internal expect class NativeSnapshotRenderTarget : AutoCloseable {
  fun attach(map: MapHandle, extent: MapExtent): RenderSessionHandle

  fun <T> withAccess(action: () -> T): T

  override fun close()

  companion object {
    fun select(backends: Set<MapRenderBackend>): NativeSnapshotRenderTargetPlan?
  }
}
