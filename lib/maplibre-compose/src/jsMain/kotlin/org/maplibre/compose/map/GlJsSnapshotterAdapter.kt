package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import js.objects.unsafeJso
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.compose.gljs.CanvasContextAttributes
import org.maplibre.compose.gljs.DEFAULT_WORKER_URL
import org.maplibre.compose.gljs.GlJsRuntime
import org.maplibre.compose.gljs.GlJsSubscription
import org.maplibre.compose.gljs.JumpToOptions
import org.maplibre.compose.gljs.MapOptions
import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.gljs.SetStyleOptions
import org.maplibre.compose.gljs.isTerminalStyleLoadFailure
import org.maplibre.compose.gljs.styleJson
import org.maplibre.compose.gljs.styleUrl
import org.maplibre.compose.gljs.subscribe
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.GlJsStyleBinding
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.util.toImageBitmap
import org.maplibre.compose.util.toLngLat
import web.dom.document
import web.html.HTMLCanvasElement
import web.html.HTMLElement

internal class GlJsSnapshotterAdapterFactory(private val logger: Logger?) :
  SnapshotterAdapterFactory {
  override fun create(): SnapshotterAdapter = GlJsSnapshotterAdapter(logger)
}

/** One private GL JS map and DOM target for a Web snapshotter. */
private class GlJsSnapshotterAdapter(private val logger: Logger?) : SnapshotterAdapter {
  private var open = true
  private var map: MaplibreMap? = null
  private var container: HTMLElement? = null
  private var styleBinding: GlJsStyleBinding? = null
  private var loadedBaseStyleRevision: Long? = null
  private var loadedDensity: Float? = null
  private var currentDensity = 1f
  private var styleLoadSubscription: GlJsSubscription? = null
  private var styleErrorSubscription: GlJsSubscription? = null
  private var renderSubscription: GlJsSubscription? = null
  private var terminalOperation: CompletableDeferred<Result<Unit>>? = null
  private val reconciler = StyleReconciler()
  private val cleanupFailures = mutableListOf<Throwable>()

  override suspend fun prepare(
    baseStyle: BaseStyle,
    baseStyleRevision: Long,
    request: MapSnapshotRequest,
  ): StyleBinding {
    check(open) { "The Web snapshotter is closed" }
    val currentMap = ensureMap(request)
    configure(currentMap, request)
    val current = styleBinding
    if (
      loadedBaseStyleRevision == baseStyleRevision &&
        loadedDensity == request.density &&
        current?.isLoaded == true
    ) {
      return current
    }

    current?.invalidate()
    styleBinding = null
    loadedBaseStyleRevision = null
    loadedDensity = null
    cancelStyleSubscriptions()
    val loading = CompletableDeferred<Result<Unit>>()
    terminalOperation = loading
    lateinit var loadSubscription: GlJsSubscription
    lateinit var errorSubscription: GlJsSubscription
    loadSubscription =
      currentMap.subscribe("style.load") {
        loadSubscription.cancel()
        errorSubscription.cancel()
        if (styleLoadSubscription === loadSubscription) styleLoadSubscription = null
        if (styleErrorSubscription === errorSubscription) styleErrorSubscription = null
        val binding = GlJsStyleBinding(currentMap, logger) { currentDensity }
        styleBinding?.invalidate()
        styleBinding = binding
        loadedBaseStyleRevision = baseStyleRevision
        loadedDensity = request.density
        loading.complete(Result.success(Unit))
      }
    errorSubscription =
      currentMap.subscribe("error") { event ->
        if (!event.isTerminalStyleLoadFailure()) return@subscribe
        loadSubscription.cancel()
        errorSubscription.cancel()
        if (styleLoadSubscription === loadSubscription) styleLoadSubscription = null
        if (styleErrorSubscription === errorSubscription) styleErrorSubscription = null
        val reason = event.error?.message ?: "MapLibre failed to load the snapshot style"
        loading.complete(Result.failure(IllegalStateException(reason)))
      }
    styleLoadSubscription = loadSubscription
    styleErrorSubscription = errorSubscription
    val options = unsafeJso<SetStyleOptions> { diff = false }
    try {
      when (baseStyle) {
        is BaseStyle.Uri -> currentMap.setStyle(styleUrl(baseStyle.uri), options)
        is BaseStyle.Json -> currentMap.setStyle(styleJson(baseStyle.json), options)
      }
    } catch (error: Throwable) {
      cancelStyleSubscriptions()
      loading.complete(Result.failure(error))
    }
    try {
      loading.await().getOrThrow()
    } finally {
      if (terminalOperation === loading) terminalOperation = null
    }
    return checkNotNull(styleBinding) { "MapLibre loaded a snapshot style without a binding" }
  }

  override suspend fun capture(
    request: MapSnapshotRequest,
    revision: DesiredStyleRevision,
  ): ImageBitmap {
    check(open) { "The Web snapshotter is closed" }
    val currentMap = checkNotNull(map) { "The Web snapshotter engine has not been created" }
    val binding = checkNotNull(styleBinding) { "The Web snapshotter style has not loaded" }
    reconciler.apply(binding, revision)
    configure(currentMap, request)

    val rendering = CompletableDeferred<Result<Unit>>()
    terminalOperation = rendering
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
      rendering.await().getOrThrow()
      currentMap.redraw()
      return readImage(currentMap, request)
    } finally {
      idleSubscription.cancel()
      if (renderSubscription === idleSubscription) renderSubscription = null
      if (terminalOperation === rendering) terminalOperation = null
    }
  }

  override suspend fun cancelActiveCapture(): SnapshotterEngineDisposition {
    releaseEngine(CancellationException("The Web snapshot capture was cancelled"))
    return SnapshotterEngineDisposition.RELEASED
  }

  override suspend fun close() {
    if (!open) return
    open = false
    releaseEngine(MapSnapshotterClosedException())
    if (cleanupFailures.isNotEmpty()) {
      throw AggregateCleanupException(
        "Web snapshotter cleanup failed in ${cleanupFailures.size} resource(s)",
        cleanupFailures.toList(),
      )
    }
  }

  private suspend fun ensureMap(request: MapSnapshotRequest): MaplibreMap {
    map?.let {
      return it
    }
    check(open) { "The Web snapshotter is closed" }
    val host = document.createElement("div").unsafeCast<HTMLElement>()
    host.style.cssText = GlJsMapSession.OFFSCREEN_CONTAINER_STYLE
    host.setAttribute(SNAPSHOTTER_TARGET_ATTRIBUTE, "")
    size(host, request)
    awaitDocumentBody().appendChild(host)
    container = host

    val options =
      unsafeJso<MapOptions> {
        container = host
        interactive = false
        attributionControl = false
        maplibreLogo = false
        pixelRatio = renderPixelRatio(request)
        maxCanvasSize = arrayOf(MAX_CANVAS_SIZE.toDouble(), MAX_CANVAS_SIZE.toDouble())
        canvasContextAttributes =
          unsafeJso<CanvasContextAttributes> { preserveDrawingBuffer = true }
      }
    GlJsRuntime.pointAtWorker(DEFAULT_WORKER_URL)
    return try {
      MaplibreMap(options).also { map = it }
    } catch (error: Throwable) {
      container = null
      runCatching { host.remove() }.exceptionOrNull()?.let(error::addSuppressed)
      throw error
    }
  }

  private fun configure(map: MaplibreMap, request: MapSnapshotRequest) {
    currentDensity = request.density
    container?.let { size(it, request) }
    map.setPixelRatio(renderPixelRatio(request))
    map.resize()
    val camera = request.cameraPosition
    map.jumpTo(
      unsafeJso<JumpToOptions> {
        center = camera.target.toLngLat()
        zoom = camera.zoom
        bearing = camera.bearing
        pitch = camera.tilt
      }
    )
  }

  private fun size(container: HTMLElement, request: MapSnapshotRequest) {
    val extent = request.extent()
    val pixelRatio = renderPixelRatio(request)
    val renderedWidth = (extent.width * pixelRatio).toInt()
    val renderedHeight = (extent.height * pixelRatio).toInt()
    require(renderedWidth <= MAX_CANVAS_SIZE && renderedHeight <= MAX_CANVAS_SIZE) {
      "The Web snapshot needs a ${renderedWidth}x$renderedHeight render canvas, " +
        "which exceeds MapLibre GL JS's ${MAX_CANVAS_SIZE}px canvas limit"
    }
    container.style.width = "${extent.width}px"
    container.style.height = "${extent.height}px"
  }

  private fun readImage(map: MaplibreMap, request: MapSnapshotRequest): ImageBitmap {
    val source = map.getCanvas()
    val extent = request.extent()
    val width = extent.physicalWidth
    val height = extent.physicalHeight
    val pixelRatio = renderPixelRatio(request)
    val renderedWidth = (extent.width * pixelRatio).toInt()
    val renderedHeight = (extent.height * pixelRatio).toInt()
    check(source.width == renderedWidth && source.height == renderedHeight) {
      "MapLibre rendered a ${source.width}x${source.height} snapshot canvas, expected " +
        "${renderedWidth}x$renderedHeight before fractional-density rounding"
    }
    val output = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
    output.width = width
    output.height = height
    val context = output.asDynamic().getContext("2d")
    check(context != null && context != undefined) {
      "The browser would not give a 2D context for a ${width}x$height snapshot"
    }
    if (!request.outputOptions.transparent) {
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
    val minimumRatio = 1.0 / minOf(request.width, request.height)
    return maxOf(request.density.toDouble(), minimumRatio)
  }

  private fun releaseEngine(reason: Throwable) {
    terminalOperation?.complete(Result.failure(reason))
    terminalOperation = null
    cancelStyleSubscriptions()
    renderSubscription?.cancel()
    renderSubscription = null
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

  private fun cancelStyleSubscriptions() {
    styleLoadSubscription?.cancel()
    styleLoadSubscription = null
    styleErrorSubscription?.cancel()
    styleErrorSubscription = null
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

  private fun MapSnapshotRequest.extent(): MapExtent =
    MapExtent.fromLogical(width, height, density.toDouble())

  private companion object {
    const val MAX_CANVAS_SIZE = 4_096
    const val SNAPSHOTTER_TARGET_ATTRIBUTE = "data-maplibre-compose-snapshotter"
  }
}
