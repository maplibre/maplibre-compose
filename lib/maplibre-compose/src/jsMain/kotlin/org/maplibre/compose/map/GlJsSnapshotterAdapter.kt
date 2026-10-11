package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import js.objects.unsafeJso
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.gljs.CanvasContextAttributes
import org.maplibre.compose.gljs.DefaultWorkerUrl
import org.maplibre.compose.gljs.GlJsMapEvent
import org.maplibre.compose.gljs.GlJsRuntime
import org.maplibre.compose.gljs.GlJsSubscription
import org.maplibre.compose.gljs.JumpToOptions
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.failedTile
import org.maplibre.compose.gljs.failedUrl
import org.maplibre.compose.gljs.subscribe
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.resource.GlJsRequestController
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.GlJsStyleBinding
import org.maplibre.compose.style.StyleOverrides
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.style.StyleSnapshot
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.toImageBitmap
import org.maplibre.compose.util.toLngLat
import org.maplibre.compose.util.toPaddingOptions
import web.dom.document
import web.html.HTMLCanvasElement
import web.html.HTMLElement

/** One private GL JS map and DOM target for a Web snapshotter. */
internal class GlJsSnapshotterAdapter(
  private val logger: MapLog?,
  private val requests: GlJsRequestController?,
) : SnapshotterAdapter {
  private var open = true
  private var map: MaplibreMap? = null
  private var container: HTMLElement? = null
  private var styleBinding: GlJsStyleBinding? = null
  private var loadedBaseStyleRevision: Long? = null
  private var loadedDensity: Float? = null
  private var currentDensity = 1f
  private var styleSubscription: GlJsSubscription? = null
  private var renderSubscription: GlJsSubscription? = null
  private var terminalOperation: CompletableDeferred<Result<Unit>>? = null

  /**
   * The first tile or resource of the loaded style that failed to load. GL JS keeps a failed tile
   * failed, so a failure fails every capture until the style loads again.
   */
  private var loadFailure: Throwable? = null

  /** Fails the capture that is waiting for the map to finish loading. */
  private var loadFailed: ((Throwable) -> Unit)? = null
  private val reconciler = StyleReconciler()
  private val cleanupFailures = mutableListOf<Throwable>()

  override fun validate(request: MapSnapshotRequest) {
    val extent = request.extent()
    val pixelRatio = renderPixelRatio(request)
    val renderedWidth = (extent.width * pixelRatio).roundToInt()
    val renderedHeight = (extent.height * pixelRatio).roundToInt()
    require(renderedWidth <= MaxCanvasSize && renderedHeight <= MaxCanvasSize) {
      "The Web snapshot needs a ${renderedWidth}x$renderedHeight render canvas, " +
        "which exceeds MapLibre GL JS's ${MaxCanvasSize}px canvas limit"
    }
  }

  override suspend fun prepare(
    baseStyle: BaseStyle,
    baseStyleRevision: Long,
    styleOverrides: StyleOverrides,
    request: MapSnapshotRequest,
  ): SnapshotPreparation {
    check(open) { "The Web snapshotter is closed" }
    val currentMap = ensureMap(request)
    configure(currentMap, request)
    val current = styleBinding
    if (
      loadedBaseStyleRevision == baseStyleRevision &&
        loadedDensity == request.density.density &&
        current?.isLoaded == true
    ) {
      if (reconciler.applyProjectionOverride(current, styleOverrides.definition().projection)) {
        currentMap.redraw()
      }
      return SnapshotPreparation(current, readViewport(currentMap, request))
    }

    current?.invalidate()
    styleBinding = null
    loadedBaseStyleRevision = null
    loadedDensity = null
    cancelStyleSubscription()
    val loading = CompletableDeferred<Result<Unit>>()
    terminalOperation = loading
    loadFailure = null
    try {
      styleSubscription =
        currentMap.loadBaseStyle(
          baseStyle,
          onLoaded = {
            val binding =
              GlJsStyleBinding(
                currentMap,
                logger,
                customTileFailed = { recordLoadFailure(it.asProviderFailure()) },
              ) {
                currentDensity
              }
            styleBinding?.invalidate()
            styleBinding = binding
            loadedBaseStyleRevision = baseStyleRevision
            loadedDensity = request.density.density
            loading.complete(Result.success(Unit))
          },
          onFailed = { message ->
            val reason = message ?: "MapLibre failed to load the snapshot style"
            // The `error` event that names the style URL reached recordLoadError first.
            loading.complete(Result.failure(loadFailure ?: IllegalStateException(reason)))
          },
        )
    } catch (error: Throwable) {
      loading.complete(Result.failure(error))
    }
    try {
      loading.await().getOrThrow()
    } finally {
      if (terminalOperation === loading) terminalOperation = null
    }
    val binding =
      checkNotNull(styleBinding) { "MapLibre loaded a snapshot style without a binding" }
    if (reconciler.applyProjectionOverride(binding, styleOverrides.definition().projection)) {
      currentMap.redraw()
    }
    return SnapshotPreparation(binding, readViewport(currentMap, request))
  }

  /**
   * Reads the viewport the next capture renders. GL JS adopts the resize and camera of [configure]
   * synchronously, and the loaded style's projection shapes the bounds, so this runs after both.
   */
  private fun readViewport(map: MaplibreMap, request: MapSnapshotRequest): Viewport {
    val extent = request.extent()
    return map.readViewport(
      extent.width.toDouble(),
      extent.height.toDouble(),
      viewportInsets = DpPadding.Zero.toPaddingOptions(),
    )
  }

  override suspend fun apply(revision: StyleSnapshot) {
    check(open) { "The Web snapshotter is closed" }
    val binding = checkNotNull(styleBinding) { "The Web snapshotter style has not loaded" }
    reconciler.apply(binding, revision)
  }

  override suspend fun capture(request: MapSnapshotRequest): ImageBitmap {
    check(open) { "The Web snapshotter is closed" }
    val currentMap = checkNotNull(map) { "The Web snapshotter engine has not been created" }
    configure(currentMap, request)

    val rendering = CompletableDeferred<Result<Unit>>()
    terminalOperation = rendering
    // GL JS fires no idle after a tile fails, so the failure ends the wait.
    loadFailure?.let { rendering.complete(Result.failure(it)) }
    loadFailed = { rendering.complete(Result.failure(it)) }
    lateinit var idleSubscription: GlJsSubscription
    idleSubscription =
      currentMap.subscribe("idle") {
        idleSubscription.cancel()
        if (renderSubscription === idleSubscription) renderSubscription = null
        rendering.complete(Result.success(Unit))
      }
    renderSubscription = idleSubscription
    currentMap.redraw()
    try {
      rendering.await().onFailure { unloadStyle() }.getOrThrow()
      currentMap.redraw()
      return readImage(currentMap, request)
    } finally {
      loadFailed = null
      idleSubscription.cancel()
      if (renderSubscription === idleSubscription) renderSubscription = null
      if (terminalOperation === rendering) terminalOperation = null
    }
  }

  /** Makes the next capture load the style again, which loads its failed tiles again. */
  private fun unloadStyle() {
    runCatching { styleBinding?.invalidate() }.exceptionOrNull()?.let(cleanupFailures::add)
    styleBinding = null
    loadedBaseStyleRevision = null
    loadedDensity = null
  }

  /**
   * Records an `error` event that names a source or a request URL: a tile, TileJSON or sprite that
   * failed to load. A refused style write names neither.
   */
  private fun recordLoadError(event: GlJsMapEvent) {
    val sourceId = event.sourceId
    val url = event.failedUrl()
    val failed =
      when {
        sourceId != null ->
          event.failedTile()?.let { "Tile $it of source '$sourceId'" } ?: "Source '$sourceId'"
        url != null -> "'$url'"
        else -> return
      }
    val cause = event.error as? Throwable
    recordLoadFailure(
      IllegalStateException("$failed failed to load: ${event.error?.message}", cause)
    )
  }

  private fun recordLoadFailure(failure: Throwable) {
    if (loadFailure != null) return
    loadFailure = failure
    loadFailed?.invoke(failure)
  }

  override suspend fun cancelActiveCapture(): SnapshotterEngineDisposition {
    releaseEngine(CancellationException("The Web snapshot capture was cancelled"))
    return SnapshotterEngineDisposition.Released
  }

  override suspend fun close() {
    if (!open) return
    open = false
    releaseEngine(snapshotterClosedCancellation())
    cleanupFailures.cleanupResult("Web snapshotter").getOrThrow()
  }

  private suspend fun ensureMap(request: MapSnapshotRequest): MaplibreMap {
    map?.let {
      return it
    }
    check(open) { "The Web snapshotter is closed" }
    val host = document.createElement("div").unsafeCast<HTMLElement>()
    host.style.cssText = GlJsMapSession.OffscreenContainerStyle
    host.setAttribute(SnapshotterTargetAttribute, "")
    size(host, request)
    awaitDocumentBody().appendChild(host)
    container = host

    val options =
      headlessMapOptions(host, renderPixelRatio(request), requests) {
        maxCanvasSize = arrayOf(MaxCanvasSize.toDouble(), MaxCanvasSize.toDouble())
        canvasContextAttributes =
          unsafeJso<CanvasContextAttributes> { preserveDrawingBuffer = true }
      }
    GlJsRuntime.pointAtWorker(DefaultWorkerUrl)
    return try {
      MaplibreMap(options).also {
        map = it
        it.subscribe("error", ::recordLoadError)
      }
    } catch (error: Throwable) {
      container = null
      runCatching { host.remove() }.exceptionOrNull()?.let(error::addSuppressed)
      throw error
    }
  }

  private fun configure(map: MaplibreMap, request: MapSnapshotRequest) {
    currentDensity = request.density.density
    container?.let { size(it, request) }
    map.setPixelRatio(renderPixelRatio(request))
    map.resize()
    val camera = request.cameraPosition
    map.jumpTo(
      unsafeJso<JumpToOptions> {
        center = camera.center.toLngLat()
        zoom = camera.zoom
        bearing = camera.bearing
        pitch = camera.pitch
        padding = camera.padding.toPaddingOptions()
      }
    )
  }

  private fun size(container: HTMLElement, request: MapSnapshotRequest) {
    val extent = request.extent()
    container.style.width = "${extent.width}px"
    container.style.height = "${extent.height}px"
  }

  private fun readImage(map: MaplibreMap, request: MapSnapshotRequest): ImageBitmap {
    val source = map.getCanvas()
    val extent = request.extent()
    val width = extent.physicalWidth
    val height = extent.physicalHeight
    val pixelRatio = renderPixelRatio(request)
    val renderedWidth = (extent.width * pixelRatio).roundToInt()
    val renderedHeight = (extent.height * pixelRatio).roundToInt()
    check(source.width == renderedWidth && source.height == renderedHeight) {
      "MapLibre rendered a ${source.width}x${source.height} snapshot canvas, expected " +
        "${renderedWidth}x$renderedHeight at pixel ratio $pixelRatio"
    }
    val output = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
    output.width = width
    output.height = height
    val context = output.asDynamic().getContext("2d")
    check(context != null && context != undefined) {
      "The browser would not give a 2D context for a ${width}x$height snapshot"
    }
    if (!request.transparent) {
      context.fillStyle = "#ffffff"
      context.fillRect(0, 0, width, height)
    }
    context.drawImage(source, 0, 0, width, height)
    val data = context.getImageData(0, 0, width, height).data
    val pixels =
      IntArray(width * height) { index ->
        val offset = index * 4
        (data[offset].unsafeCast<Int>() shl 16) or
          (data[offset + 1].unsafeCast<Int>() shl 8) or
          data[offset + 2].unsafeCast<Int>() or
          (data[offset + 3].unsafeCast<Int>() shl 24)
      }
    return pixels.toImageBitmap(width, height)
  }

  private fun renderPixelRatio(request: MapSnapshotRequest): Double {
    val extent = request.extent()
    val minimumRatio = 1.0 / minOf(extent.width, extent.height)
    return maxOf(request.density.density.toDouble(), minimumRatio)
  }

  private fun releaseEngine(reason: Throwable) {
    terminalOperation?.complete(Result.failure(reason))
    terminalOperation = null
    cancelStyleSubscription()
    renderSubscription?.cancel()
    renderSubscription = null
    loadFailure = null
    loadFailed = null
    runCatching { styleBinding?.invalidate() }.exceptionOrNull()?.let(cleanupFailures::add)
    styleBinding = null
    loadedBaseStyleRevision = null
    loadedDensity = null
    val currentMap = map
    map = null
    runCatching { currentMap?.remove() }.exceptionOrNull()?.let(cleanupFailures::add)
    val currentContainer = container
    container = null
    runCatching { currentContainer?.remove() }.exceptionOrNull()?.let(cleanupFailures::add)
  }

  private fun cancelStyleSubscription() {
    styleSubscription?.cancel()
    styleSubscription = null
  }

  private suspend fun awaitDocumentBody(): HTMLElement {
    documentBodyOrNull()?.let {
      return it
    }
    return suspendCancellableCoroutine { continuation ->
      val dynamicDocument = document.asDynamic()
      lateinit var listener: (dynamic) -> Unit
      listener = {
        val body = documentBodyOrNull()
        if (body != null) {
          dynamicDocument.removeEventListener("DOMContentLoaded", listener)
          if (continuation.isActive) continuation.resume(body)
        }
      }
      dynamicDocument.addEventListener("DOMContentLoaded", listener)
      continuation.invokeOnCancellation {
        dynamicDocument.removeEventListener("DOMContentLoaded", listener)
      }
      listener(null)
    }
  }

  private fun documentBodyOrNull(): HTMLElement? {
    val body = document.asDynamic().body
    return if (body == null) null else body.unsafeCast<HTMLElement>()
  }

  private companion object {
    const val MaxCanvasSize = 4_096
    const val SnapshotterTargetAttribute = "data-maplibre-compose-snapshotter"
  }
}
