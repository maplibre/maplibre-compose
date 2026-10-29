package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiRuntime
import org.maplibre.compose.mlnffi.use
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.LoadedStyleContents
import org.maplibre.compose.style.MlnFfiRenderSessions
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.style.UnspecifiedBaseStyle
import org.maplibre.compose.util.toCameraOptions
import org.maplibre.compose.util.toImageBitmap
import org.maplibre.compose.util.unpremultiplyChannel
import org.maplibre.nativeffi.CameraUpdate
import org.maplibre.nativeffi.EdgeInsets
import org.maplibre.nativeffi.GraphicsDeviceHandle
import org.maplibre.nativeffi.GraphicsDeviceTextureDescriptor
import org.maplibre.nativeffi.LogicalExtent
import org.maplibre.nativeffi.MapHandle
import org.maplibre.nativeffi.MapMode
import org.maplibre.nativeffi.MapOptions
import org.maplibre.nativeffi.MapProjectionHandle
import org.maplibre.nativeffi.MaplibreException
import org.maplibre.nativeffi.RenderDriverKind
import org.maplibre.nativeffi.RenderSessionAttachOptions
import org.maplibre.nativeffi.RenderSessionHandle
import org.maplibre.nativeffi.RuntimeEvent
import org.maplibre.nativeffi.RuntimeEventFlag
import org.maplibre.nativeffi.RuntimeEventType
import org.maplibre.nativeffi.TextureReadbackResult

private val SnapshotEvents =
  RuntimeEventFlag.MAP_STYLE_LOADED or RuntimeEventFlag.MAP_LOADING_FAILED

internal fun createNativeSnapshotterAdapter(owner: MlnFfiRuntime): SnapshotterAdapter =
  createNativeSnapshotterAdapter(owner, tryLoadRuntimeBackends())

internal fun createNativeSnapshotterAdapter(
  owner: MlnFfiRuntime,
  backends: Result<Set<MapRenderBackend>>,
): SnapshotterAdapter {
  val available = backends.getOrElse { error ->
    throw MapSnapshotException("Could not load the MapLibre Native FFI runtime", error)
  }
  if (available.isEmpty())
    throw MapSnapshotException("No MapLibre Native render backend is available")
  return NativeSnapshotterAdapter(owner)
}

/**
 * One private static map, an offscreen render session that renders on its native core worker, and a
 * retained reconciler for a native snapshotter. The common snapshotter runs one step at a time.
 */
private class NativeSnapshotterAdapter(
  private val owner: MlnFfiRuntime,
) : SnapshotterAdapter {
  @Volatile private var open = true
  @Volatile private var engine: NativeSnapshotEngine? = null
  @Volatile private var styleBinding: MlnFfiStyleBinding? = null
  @Volatile private var loadedBaseStyleRevision: Long? = null
  @Volatile private var currentDensity = 1f

  /** The first custom source provider exception during the current capture. */
  @Volatile private var providerFailure: Throwable? = null
  private val reconciler = StyleReconciler()

  override suspend fun prepare(
    baseStyle: BaseStyle,
    baseStyleRevision: Long,
    request: MapSnapshotRequest,
  ): SnapshotPreparation {
    val current = ensureEngine(request)
    currentDensity = request.density.density
    configureRequest(current, request)
    val binding = styleBinding
    if (baseStyleRevision == loadedBaseStyleRevision && binding?.isLoaded == true) {
      return SnapshotPreparation(binding, readViewport(current, request))
    }

    binding?.invalidate()
    styleBinding = null
    loadedBaseStyleRevision = null
    try {
      loadStyle(current, baseStyle)
    } catch (error: Throwable) {
      // A cancelled load can still report its style, which must not satisfy the next load.
      current.replace = true
      throw error
    }
    loadedBaseStyleRevision = baseStyleRevision
    return SnapshotPreparation(
      binding = checkNotNull(styleBinding) { "MapLibre reported a loaded style without a binding" },
      viewport = readViewport(current, request),
    )
  }

  override suspend fun apply(revision: StyleSnapshot): Boolean {
    val binding = checkNotNull(styleBinding) { "A snapshot style has not loaded" }
    val prepared = reconciler.prepare(binding, revision)
    providerFailure = null
    binding.commandGroup { reconciler.apply(binding, prepared) }
    return reconciler.settle(binding)
  }

  override suspend fun capture(request: MapSnapshotRequest): ImageBitmap {
    checkNotNull(styleBinding) { "A snapshot style has not loaded" }.awaitGeoJsonUpdates()
    val current = checkNotNull(engine) { "The snapshotter engine map is closed" }
    configureRequest(current, request)
    try {
      current.map.requestStillImage().await()
      current.session.drainFrameResults()
      providerFailure?.let { throw it }
      val pixels = current.session.readTexture().await()
      // Conversion visits every pixel, so it stays off main.
      return withContext(Dispatchers.Default) { readImage(pixels, request) }
    } catch (error: Throwable) {
      // MapLibre keeps a failed tile or glyph range failed and would not load it again, and a
      // cancelled capture leaves its still image pending, so the next capture needs a new engine.
      current.replace = true
      // A provider's failure is reported before the tile error it causes, so it explains a still
      // image that failed on that tile.
      if (error is CancellationException) throw error
      throw providerFailure ?: error
    }
  }

  /** A cancelled capture leaves a still image pending, so release its private map. */
  override suspend fun cancelActiveCapture(): SnapshotterEngineDisposition {
    val current = engine ?: return SnapshotterEngineDisposition.Retained
    if (!current.replace) return SnapshotterEngineDisposition.Retained
    val failures = mutableListOf<Throwable>()
    styleBinding?.invalidate()
    styleBinding = null
    loadedBaseStyleRevision = null
    releaseEngine(failures)
    failures.cleanupResult("Native snapshotter").getOrThrow()
    return SnapshotterEngineDisposition.Released
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
    runCatching { current.close() }.exceptionOrNull()?.let(failures::add)
  }

  private suspend fun ensureEngine(request: MapSnapshotRequest): NativeSnapshotEngine {
    val extent = request.extent()
    engine?.let {
      if (it.scaleFactor == extent.scaleFactor && !it.replace) return it
    }
    if (engine != null) {
      val failures = mutableListOf<Throwable>()
      runCatching { styleBinding?.invalidate() }.exceptionOrNull()?.let(failures::add)
      styleBinding = null
      loadedBaseStyleRevision = null
      releaseEngine(failures)
      failures.cleanupResult("Native snapshotter").getOrThrow()
    }
    return NativeSnapshotEngine.create(owner, extent).also { engine = it }
  }

  /** Applies the request's size, then its camera, and waits until the map has both. */
  private suspend fun configureRequest(current: NativeSnapshotEngine, request: MapSnapshotRequest) {
    val extent = request.extent()
    check(current.scaleFactor == extent.scaleFactor) {
      "The snapshot engine scale does not match the capture request"
    }
    // The session's resize completes only once the map publishes an update at the new extent,
    // which a static map does only for a loaded style, and a detach waits behind it. So resize
    // only for a new extent. The resize submits the map's resize at once, so the camera command
    // after it observes the new size when it completes.
    if (current.extent != extent) {
      current.extent = extent
      current.session.discarding.resize(extent.toFfiExtent())
    }
    current.map
      .updateCamera(CameraUpdate(camera = request.cameraPosition.toCameraOptions(EdgeInsets())))
      .await()
  }

  private suspend fun loadStyle(current: NativeSnapshotEngine, baseStyle: BaseStyle) {
    val load = current.expectStyle()
    // This snapshot's own commands are the map's only ones, so an event of a later generation
    // than the latest published one comes from this style command or after it.
    load.minimumGeneration = current.map.getSnapshot().generation + 1u
    val submitted =
      try {
        when (baseStyle) {
          is BaseStyle.Uri -> current.map.setStyleUrl(baseStyle.uri)
          is BaseStyle.Json -> current.map.setStyleJson(baseStyle.json)
          UnspecifiedBaseStyle -> error("UnspecifiedBaseStyle is never created")
        }
      } catch (error: MaplibreException) {
        current.endStyleLoad(load)
        throw IllegalStateException(error.message ?: "MapLibre snapshot capture failed", error)
      }
    try {
      submitted.await()
    } catch (error: Throwable) {
      current.endStyleLoad(load)
      throw error
    }
    load.result.await().getOrThrow()
    styleBinding?.invalidate()
    styleBinding = createStyleBinding(current)
  }

  private suspend fun readViewport(
    current: NativeSnapshotEngine,
    request: MapSnapshotRequest,
  ): Viewport {
    val extent = request.extent()
    // A snapshot is read once and never republished, so its extents are read with its camera.
    val geometry = current.map.getSnapshot().viewportGeometry(EdgeInsets())
    val (extents, metersPerDpAtCenter) =
      MapProjectionHandle.create(current.map.getSnapshot().view).use { projection ->
        MapViewportExtents(unprojectedCorners(projection, geometry.size)) to
          projection.metersPerPixelAtLatitude(geometry.camera.center.latitude.coerceIn(-90.0, 90.0))
      }
    check(
      geometry.size.width.value.toInt() == extent.width &&
        geometry.size.height.value.toInt() == extent.height,
    ) {
      "The snapshot map reported a ${geometry.size} viewport, expected " +
        "${extent.width}x${extent.height} logical pixels"
    }
    return Viewport(
        cameraPosition = geometry.camera,
        size = geometry.size,
        visibleBounds = extents.bounds,
        visibleRegion = extents.region,
        metersPerDpAtCenter = metersPerDpAtCenter,
      )
      .withProjection(MapViewProjection(current.map.getSnapshot().view))
  }

  private fun createStyleBinding(current: NativeSnapshotEngine): MlnFfiStyleBinding =
    MlnFfiStyleBinding(
      map = current.map,
      owner = owner,
      contents =
        current.loadedContents ?: error("MapLibre reported a loaded style without contents"),
      loggerProvider = { owner.options.logger },
      sessionOpen = { open && engine === current },
      renderSessions =
        object : MlnFfiRenderSessions {
          override fun readySession(): RenderSessionHandle = current.session
        },
      getScale = { currentDensity },
      customTileFailed = { error ->
        if (providerFailure == null) providerFailure = error.asProviderFailure()
      },
    )

  private fun readImage(
    readback: TextureReadbackResult,
    request: MapSnapshotRequest,
  ): ImageBitmap {
    val expected = request.extent()
    val info = readback.info
    val width = info.width.toInt()
    val height = info.height.toInt()
    val stride = info.stride.toInt()
    val bytes = readback.data
    check(width == expected.physicalWidth && height == expected.physicalHeight) {
      "Snapshot readback was ${width}x$height, expected " +
        "${expected.physicalWidth}x${expected.physicalHeight}"
    }
    val transparent = request.transparent
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val source = y * stride + x * 4
        val alpha = bytes[source + 3].toInt() and 0xff
        val red = unpremultiplyChannel(bytes[source].toInt() and 0xff, alpha)
        val green = unpremultiplyChannel(bytes[source + 1].toInt() and 0xff, alpha)
        val blue = unpremultiplyChannel(bytes[source + 2].toInt() and 0xff, alpha)
        pixels[y * width + x] =
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
    return pixels.toImageBitmap(width, height)
  }
}

/** One style load that runtime events finish with the load or failure they report. */
private class StyleLoad {
  val result = CompletableDeferred<Result<Unit>>()

  /** The generation of the style command; events from before it belong to the replaced style. */
  @Volatile var minimumGeneration: ULong = ULong.MAX_VALUE
}

/** A static map and the owned-texture render session it renders into. */
private class NativeSnapshotEngine
private constructor(
  private val owner: MlnFfiRuntime,
  val map: MapHandle,
  val scaleFactor: Double,
  /** The extent the session last sized its target to. */
  @Volatile var extent: MapExtent,
) : MlnFfiRuntime.MapChild {
  lateinit var session: RenderSessionHandle
    private set

  @Volatile private var styleLoad: StyleLoad? = null
  @Volatile
  var loadedContents: LoadedStyleContents? = null
    private set

  /**
   * Whether a step failed. MapLibre keeps a failed tile or glyph range failed and would not load it
   * again, so the next capture needs a new engine.
   */
  @Volatile var replace = false

  fun expectStyle(): StyleLoad =
    StyleLoad().also {
      loadedContents = null
      styleLoad = it
    }

  fun endStyleLoad(load: StyleLoad) {
    if (styleLoad === load) styleLoad = null
  }

  override fun onEvent(event: RuntimeEvent) {
    when (event.type) {
      RuntimeEventType.MAP_STYLE_LOADED -> {
        val load = styleLoad ?: return
        if (event.generation < load.minimumGeneration) return
        owner.launchOnMain {
          val contents = LoadedStyleContents.read(map)
          if (styleLoad !== load) return@launchOnMain
          loadedContents = contents
          styleLoad = null
          load.result.complete(Result.success(Unit))
        }
      }
      RuntimeEventType.MAP_LOADING_FAILED -> {
        val load = styleLoad ?: return
        if (event.generation < load.minimumGeneration) return
        styleLoad = null
        val message = event.message.ifBlank { "MapLibre snapshot capture failed" }
        load.result.complete(Result.failure(IllegalStateException(message)))
      }
      else -> Unit
    }
  }

  suspend fun close() =
    withContext(NonCancellable) {
      val failures = mutableListOf<Throwable>()
      styleLoad?.result?.complete(Result.failure(snapshotterClosedCancellation()))
      if (::session.isInitialized) {
        runCatching { session.release().await() }.exceptionOrNull()?.let(failures::add)
      }
      owner.unregister(map)
      runCatching { map.release().await() }.exceptionOrNull()?.let(failures::add)
      failures.cleanupResult("Native snapshotter").getOrThrow()
    }

  companion object {
    suspend fun create(
      owner: MlnFfiRuntime,
      extent: MapExtent,
    ): NativeSnapshotEngine {
      val runtime = owner.awaitRuntime()
      val (map, ready) =
        runtime.createMap(
          MapOptions(
            initialExtent = extent.toFfiExtent(),
            mapMode = MapMode.STATIC,
            eventMask = SnapshotEvents,
          ),
        )
      val engine = NativeSnapshotEngine(owner, map, extent.scaleFactor, extent)
      try {
        check(owner.register(map, engine)) { "The map runtime is closed" }
        ready.await()
        val attachment =
          GraphicsDeviceHandle.create().use { device ->
            map.attachGraphicsDeviceTexture(
              device,
              GraphicsDeviceTextureDescriptor(extent.toFfiExtent()),
              RenderSessionAttachOptions(driver = RenderDriverKind.CORE_WORKER),
            )
          }
        engine.session = attachment.session
        attachment.ready.await()
        return engine
      } catch (error: Throwable) {
        runCatching { engine.close() }.exceptionOrNull()?.let(error::addSuppressed)
        throw error
      }
    }
  }
}

/** MapLibre rejects an extent with a zero side. */
internal fun MapExtent.toFfiExtent() =
  LogicalExtent(
    width = width.coerceAtLeast(1).toUInt(),
    height = height.coerceAtLeast(1).toUInt(),
    scaleFactor = scaleFactor,
  )
