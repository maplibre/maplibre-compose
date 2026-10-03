package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.interaction.internal.select
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiRenderSessions
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.util.rethrowIfFatal
import org.maplibre.compose.util.toCameraOptions
import org.maplibre.compose.util.toImageBitmap
import org.maplibre.compose.util.unpremultiplyChannel
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

internal fun createNativeSnapshotterAdapter(
  owner: MlnFfiRuntime,
  backends: Set<MapRenderBackend> = loadRuntimeBackends(owner.options.logger),
): SnapshotterAdapter {
  val targetPlan =
    NativeSnapshotRenderTarget.select(backends)
      ?: throw UnsupportedOperationException(
        "No compatible offscreen snapshot backend is available from ${backends.joinToString()}"
      )
  return NativeSnapshotterAdapter(owner, targetPlan)
}

/** One private map, offscreen render session, and retained reconciler for a native snapshotter. */
private class NativeSnapshotterAdapter(
  private val owner: MlnFfiRuntime,
  private val targetPlan: NativeSnapshotRenderTargetPlan,
) : SnapshotterAdapter {
  @Volatile private var open = true
  @Volatile private var engine: NativeSnapshotEngine? = null
  @Volatile private var styleBinding: MlnFfiStyleBinding? = null
  @Volatile private var loadedBaseStyleRevision: Long? = null
  @Volatile private var currentDensity = 1f
  @Volatile private var terminalOperation: NativeSnapshotOperation? = null
  private val reconciler = StyleReconciler()

  override suspend fun prepare(
    baseStyle: BaseStyle,
    baseStyleRevision: Long,
    request: MapSnapshotRequest,
  ): SnapshotPreparation = runNativeRequest {
    owner.awaitReady()
    ensureEngine(request)
    currentDensity = request.density
    configureRequest(request)
    val current = styleBinding
    if (baseStyleRevision == loadedBaseStyleRevision && current?.isLoaded == true) {
      return@runNativeRequest SnapshotPreparation(current, readViewport(request))
    }

    current?.invalidate()
    styleBinding = null
    loadedBaseStyleRevision = null
    val loading = NativeSnapshotOperation(NativeSnapshotOperation.Awaits.Style)
    terminalOperation = loading
    submitStyle(loading, baseStyle)
    val loadResult = loading.completion.await()
    if (terminalOperation === loading) terminalOperation = null
    loadResult.getOrThrow()
    loadedBaseStyleRevision = baseStyleRevision
    SnapshotPreparation(
      binding = checkNotNull(styleBinding) { "MapLibre reported a loaded style without a binding" },
      viewport = readViewport(request),
    )
  }

  override suspend fun capture(
    request: MapSnapshotRequest,
    revision: StyleSnapshot,
  ): ImageBitmap = runNativeRequest {
    val binding = checkNotNull(styleBinding) { "A snapshot style has not loaded" }
    val prepared = reconciler.prepare(binding, revision)
    checkNotNull(engine?.loop?.await { reconciler.apply(binding, prepared) }) {
      "The snapshotter engine map stopped during style reconciliation"
    }
    binding.awaitGeoJsonUpdates()
    configureRequest(request)
    // The owner thread renders the still image from its update events; see handleEvent.
    val rendering = NativeSnapshotOperation(NativeSnapshotOperation.Awaits.StillImage)
    terminalOperation = rendering
    submitOperation(rendering) { map -> map.requestStillImage() }
    val renderResult = rendering.completion.await()
    if (terminalOperation === rendering) terminalOperation = null
    renderResult.getOrThrow()
    readImage(request)
  }

  /**
   * The owner thread finishes an abandoned operation on its own, including a still image, so the
   * engine is idle once that operation completes.
   */
  override suspend fun cancelActiveCapture(): SnapshotterEngineDisposition {
    terminalOperation?.completion?.await()
    return SnapshotterEngineDisposition.Retained
  }

  override suspend fun close() {
    if (!open) return
    open = false
    val failures = mutableListOf<Throwable>()
    runCatching { styleBinding?.invalidate() }.exceptionOrNull()?.let(failures::add)
    styleBinding = null
    loadedBaseStyleRevision = null
    releaseEngine(failures)
    failures.cleanupResult("Native snapshotter").getOrThrow()
  }

  private suspend fun releaseEngine(failures: MutableList<Throwable>) {
    val current = engine ?: return
    engine = null
    current.loop.close()
    runCatching { current.loop.awaitClosed() }.exceptionOrNull()?.let(failures::add)
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
      failures.cleanupResult("Native snapshotter").getOrThrow()
    }
    val created = NativeSnapshotOperation(NativeSnapshotOperation.Awaits.Owner)
    val resources = NativeSnapshotRenderResources(extent, targetPlan)
    lateinit var candidate: NativeSnapshotEngine
    val candidateLoop =
      MlnFfiMapRuntimeLoop(
        extent = extent,
        owner = owner,
        getLogger = { owner.options.logger },
        onMapCreated = resources::attach,
        onMapPublished = { created.completion.complete(Result.success(Unit)) },
        onMapClosing = { resources.close() },
        onEvent = { map, event -> handleEvent(candidate, map, event) },
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
      candidateLoop.close()
      runCatching { candidateLoop.awaitClosed() }.exceptionOrNull()?.let(error::addSuppressed)
      throw error
    }
  }

  private suspend fun configureRequest(request: MapSnapshotRequest) {
    val currentEngine = checkNotNull(engine)
    val currentLoop = currentEngine.loop
    val extent = request.extent()
    val resized = NativeSnapshotOperation(NativeSnapshotOperation.Awaits.Owner)
    terminalOperation = resized
    try {
      checkNotNull(
        currentLoop.await(
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
      // The settle completes the operation even for a cancelled caller, which waits for it anyway.
      withContext(NonCancellable) {
        val settled = runCatching { currentLoop.awaitEventsDrained() }
        resized.completion.complete(
          if (settled.isSuccess) settled
          else Result.failure(currentLoop.failure ?: snapshotterClosedCancellation())
        )
      }
    } catch (error: Throwable) {
      resized.completion.complete(Result.failure(error))
    }
    // The settle ignored cancellation, so a cancelled caller stops here instead of rendering.
    currentCoroutineContext().ensureActive()
    val resizeResult = resized.completion.await()
    if (terminalOperation === resized) terminalOperation = null
    resizeResult.getOrThrow()
  }

  /**
   * Reads the viewport the next capture renders. Runs after the base style has loaded, because the
   * style's projection shapes the visible region and bounds.
   */
  private suspend fun readViewport(request: MapSnapshotRequest): Viewport {
    val currentEngine = checkNotNull(engine)
    val extent = request.extent()
    // A snapshot is read once and never republished, so its extents are read with its camera.
    val read =
      checkNotNull(
        currentEngine.loop.await(
          action = { map ->
            val geometry = map.readViewportGeometry(EdgeInsets.ZERO)
            map.createProjection().use { projection ->
              Triple(
                geometry,
                MapViewportExtents(unprojectedCorners(projection, geometry.size)),
                projection.metersPerPixelAtLatitude(
                  geometry.camera.target.latitude.coerceIn(-90.0, 90.0)
                ),
              )
            }
          }
        )
      ) {
        "The snapshotter engine map stopped before its viewport could be read"
      }
    val (applied, extents, metersPerDpAtTarget) = read
    check(
      applied.size.width.value.toInt() == extent.width &&
        applied.size.height.value.toInt() == extent.height
    ) {
      "The snapshot map reported a ${applied.size} viewport, expected " +
        "${extent.width}x${extent.height} logical pixels"
    }
    return Viewport(
      cameraPosition = applied.camera,
      size = applied.size,
      visibleBounds = extents.bounds,
      visibleRegion = extents.region,
      metersPerDpAtTarget = metersPerDpAtTarget,
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

  private fun handleEvent(source: NativeSnapshotEngine, map: MapHandle, event: RuntimeEvent) {
    if (engine !== source) return
    val operation = terminalOperation
    when (event.type) {
      RuntimeEventType.MAP_STYLE_LOADED -> {
        if (operation?.awaits != NativeSnapshotOperation.Awaits.Style) return
        val binding = createStyleBinding(source, map)
        styleBinding?.invalidate()
        styleBinding = binding
        operation.completion.complete(Result.success(Unit))
      }
      RuntimeEventType.MAP_LOADING_FAILED -> {
        if (operation?.awaits != NativeSnapshotOperation.Awaits.Style) return
        val message = event.message.ifBlank { "MapLibre snapshot capture failed" }
        operation.completion.complete(Result.failure(IllegalStateException(message)))
      }
      RuntimeEventType.MAP_STILL_IMAGE_FAILED,
      RuntimeEventType.MAP_RENDER_ERROR -> {
        if (operation?.awaits != NativeSnapshotOperation.Awaits.StillImage) return
        val message = event.message.ifBlank { "MapLibre snapshot capture failed" }
        operation.completion.complete(Result.failure(IllegalStateException(message)))
      }
      // A still image progresses only inside renderUpdate, and NO_UPDATE and SIZE_PENDING wait for
      // the next MAP_RENDER_UPDATE_AVAILABLE, so each update event gets one render.
      RuntimeEventType.MAP_RENDER_UPDATE_AVAILABLE -> {
        if (operation?.awaits != NativeSnapshotOperation.Awaits.StillImage) return
        renderStillImage(source, operation)
      }
      RuntimeEventType.MAP_STILL_IMAGE_FINISHED -> {
        if (operation?.awaits != NativeSnapshotOperation.Awaits.StillImage) return
        operation.finished = true
        // The texture needs one rendered frame to read back.
        if (operation.rendered) operation.completeStillImage()
        else renderStillImage(source, operation)
      }
      else -> Unit
    }
  }

  /** Owner thread. Renders the latest update into the snapshot texture for [operation]. */
  private fun renderStillImage(source: NativeSnapshotEngine, operation: NativeSnapshotOperation) {
    if (operation.completion.isCompleted) return
    try {
      val update = source.resources.withSession { it.renderUpdate() }
      when (update.result) {
        RenderResult.RENDERED -> operation.rendered = true
        // Only window surfaces report this, and no update event follows it. The snapshot renders
        // into an owned texture, so fail rather than wait for an event that will not come.
        RenderResult.TARGET_NOT_READY ->
          error("The snapshot texture reported that it had no frame to render into")
        else -> Unit
      }
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      operation.completion.complete(Result.failure(error))
      return
    }
    operation.completeStillImage()
  }

  private fun createStyleBinding(source: NativeSnapshotEngine, map: MapHandle): MlnFfiStyleBinding =
    MlnFfiStyleBinding(
      map = map,
      loggerProvider = { owner.options.logger },
      sessionOpen = { open },
      loop = source.loop,
      // A snapshot renders on the owner thread, so the render session is reached from there.
      renderSessions =
        object : MlnFfiRenderSessions {
          override suspend fun <T> awaitRenderSession(action: (RenderSessionHandle) -> T): T? =
            source.loop.await { source.resources.withSessionOrNull(action) }
        },
      getScale = { currentDensity },
    )

  private suspend fun readImage(request: MapSnapshotRequest): ImageBitmap {
    val expected = request.extent()
    val currentEngine = checkNotNull(engine)
    val rgba =
      currentEngine.loop.await(
        action = { _ ->
          currentEngine.resources.withSession { session ->
            val info = session.textureImageInfo()
            NativeBuffer.allocate(info.byteLength).use { buffer ->
              val copied = session.readPremultipliedRgba8(buffer)
              Triple(copied, buffer.toByteArray(), request.transparent)
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
        val red = unpremultiplyChannel(bytes[source].toInt() and 0xff, alpha)
        val green = unpremultiplyChannel(bytes[source + 1].toInt() and 0xff, alpha)
        val blue = unpremultiplyChannel(bytes[source + 2].toInt() and 0xff, alpha)
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

  private fun submitStyle(operation: NativeSnapshotOperation, baseStyle: BaseStyle) {
    submitOperation(operation) { map ->
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

  private fun submitOperation(operation: NativeSnapshotOperation, action: (MapHandle) -> Unit) {
    val currentLoop = checkNotNull(engine).loop
    currentLoop.submit(
      onDropped = {
        operation.completion.complete(
          Result.failure(currentLoop.failure ?: snapshotterClosedCancellation())
        )
      }
    ) { map ->
      runCatching { action(map) }.onFailure { operation.completion.complete(Result.failure(it)) }
    }
  }

  private fun MapSnapshotRequest.extent(): MapExtent =
    MapExtent.fromLogical(width, height, density.toDouble())

  /** One request step that snapshot events or the owner thread complete. */
  private class NativeSnapshotOperation(val awaits: Awaits) {
    val completion = CompletableDeferred<Result<Unit>>()

    /** Still image progress. Owner thread only. */
    var finished = false
    var rendered = false

    /** Completes a still image once MapLibre finished it and a frame rendered into the texture. */
    fun completeStillImage() {
      if (finished && rendered) completion.complete(Result.success(Unit))
    }

    enum class Awaits {
      /** Owner-thread work: engine creation or a resize. */
      Owner,
      /** A style load event. */
      Style,
      /** Still image events. */
      StillImage,
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

  /** Runs [action] with the attached session, or returns null when none is attached. */
  fun <T> withSessionOrNull(action: (RenderSessionHandle) -> T): T? =
    if (target == null || session == null) null else withSession(action)

  fun close() {
    val failures = mutableListOf<Throwable>()
    val currentTarget = target
    try {
      if (currentTarget == null) release(null, failures)
      else currentTarget.withAccess { release(currentTarget, failures) }
    } catch (error: Throwable) {
      failures += error
      if (target === currentTarget) release(currentTarget, failures)
    }
    failures.cleanupResult("Native snapshotter").getOrThrow()
  }

  /** Forgets and closes the session, then [closing]. */
  private fun release(closing: NativeSnapshotRenderTarget?, failures: MutableList<Throwable>) {
    val currentSession = session
    session = null
    target = null
    runCatching { currentSession?.close() }.exceptionOrNull()?.let(failures::add)
    runCatching { closing?.close() }.exceptionOrNull()?.let(failures::add)
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
