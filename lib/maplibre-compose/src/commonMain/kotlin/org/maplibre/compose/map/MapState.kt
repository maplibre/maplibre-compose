package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.style.ClickRoute
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleCompositionHost
import org.maplibre.compose.style.StyleHostDispatcher
import org.maplibre.compose.style.StyleNode
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.style.styleHostDispatcher
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MapClickHandler
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * The map's identity, split from the composable that shows it: the style composition and its host,
 * the loaded [StyleBinding], and the wiring into the [CameraState] and [StyleState] the public API
 * hands in.
 *
 * The host, the root [StyleNode], and the content composition all live as long as this state. A
 * session ([MapAdapter]) attaches through [attachSession] and detaches through [detachSession]; the
 * style the session loads arrives through [callbacks] and re-points the persistent node, which
 * reapplies the whole desired state to the new style. The content follows the style the application
 * has *selected* while the node targets the *loaded* style; during a switch those differ and
 * nothing here reconciles them. The binding dropping writes after unload is what makes that
 * survivable.
 */
internal class MapState(
  cameraState: CameraState,
  styleState: StyleState,
  density: Density = Density(1f),
  layoutDirection: LayoutDirection = LayoutDirection.Ltr,
  logger: Logger? = null,
  inheritedLocals: CompositionLocalContext? = null,
  hostDispatcher: StyleHostDispatcher = styleHostDispatcher(),
) : AutoCloseable {

  internal val styleNode: StyleNode = StyleNode(StyleBinding.UNLOADED, logger)

  internal val host: StyleCompositionHost =
    StyleCompositionHost(
      rootNode = styleNode,
      dispatcher = hostDispatcher.dispatcher,
      density = density,
      layoutDirection = layoutDirection,
      logger = logger,
      onClosed = hostDispatcher::close,
    )

  internal var cameraState: CameraState = cameraState
    set(value) {
      if (field === value) return
      val adapter = field.map
      field.map = null
      field = value
      value.density = host.density
      value.map = adapter
    }

  internal var styleState: StyleState = styleState
    set(value) {
      if (field === value) return
      field.detach()
      field = value
      value.attach(styleNode)
    }

  internal var logger: Logger? = logger
    set(value) {
      field = value
      styleNode.logger = value
    }

  internal var density: Density
    get() = host.density
    set(value) {
      host.density = value
      cameraState.density = value
    }

  internal var layoutDirection: LayoutDirection
    get() = host.layoutDirection
    set(value) {
      host.layoutDirection = value
    }

  internal var inheritedLocals: CompositionLocalContext?
    get() = host.inheritedLocals
    set(value) {
      host.inheritedLocals = value
    }

  internal var onMapClick: MapClickHandler = { _, _ -> ClickResult.Pass }
  internal var onMapLongClick: MapClickHandler = { _, _ -> ClickResult.Pass }
  internal var onFrame: (framesPerSecond: Double) -> Unit = {}
  internal var onMapLoadFailed: (reason: String?) -> Unit = {}
  internal var onMapLoadFinished: () -> Unit = {}

  /** The scope click queries launch on; null drops clicks, which only a missing UI would cause. */
  internal var clickScope: CoroutineScope? = null

  private val contentState = mutableStateOf<(@Composable @MaplibreComposable () -> Unit)>({})

  init {
    this.cameraState.density = density
    this.host.inheritedLocals = inheritedLocals
  }

  private var contentStarted = false

  /** Replaces the style content; the host recomposes because it reads this state. */
  internal fun setStyleContent(content: @Composable @MaplibreComposable () -> Unit) {
    // A write with no read, so a UI composition calling this never subscribes to the state.
    contentState.value = content
  }

  /**
   * Starts the style composition. Called after the snapshot that constructed this state has
   * applied: content the host composes before then reads this state's records too early to be
   * invalidated by their first commit.
   */
  internal fun startStyleComposition() {
    if (contentStarted) return
    contentStarted = true
    host.setContent { contentState.value.invoke() }
  }

  /** Wires [adapter] into the camera; the style arrives later through [callbacks]. */
  internal fun attachSession(adapter: MapAdapter) {
    cameraState.map = adapter
    styleState.attach(styleNode)
  }

  /** Unwires the session; the state, its content, and its desired style survive for the next. */
  internal fun detachSession() {
    cameraState.map = null
    updateBinding(null)
    styleState.detach()
  }

  /** Applies the composable's per-composition options; attach-independent, safe to repeat. */
  internal fun applyOptions(
    map: MapAdapter,
    cameraPadding: PaddingValues,
    zoomRange: ClosedRange<Float>,
    pitchRange: ClosedRange<Float>,
    boundingBox: BoundingBox?,
    options: MapOptions,
  ) {
    map.setCameraPadding(cameraPadding)
    map.setMinZoom(zoomRange.start.toDouble())
    map.setMaxZoom(zoomRange.endInclusive.toDouble())
    map.setMinPitch(pitchRange.start.toDouble())
    map.setMaxPitch(pitchRange.endInclusive.toDouble())
    map.setRenderSettings(options.renderOptions)
    map.setGestureSettings(options.gestureOptions)
    map.setTileLodSettings(options.tileLodOptions)
    map.setCameraBoundingBox(boundingBox)
  }

  private fun updateBinding(newBinding: StyleBinding?) {
    styleNode.binding = newBinding ?: StyleBinding.UNLOADED
    styleState.refreshSources()
    host.requestApplyChanges()
  }

  override fun close() {
    detachSession()
    host.close()
  }

  internal val callbacks: MapAdapter.Callbacks =
    object : MapAdapter.Callbacks {
      override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
        updateBinding(style)
        if (cameraState.map === map) cameraState.viewportState.value = map.getViewport()
      }

      override fun onMapFailLoading(reason: String?) {
        onMapLoadFailed(reason)
      }

      override fun onMapFinishedLoading(map: MapAdapter) {
        styleState.refreshSources()
        onMapLoadFinished()
      }

      override fun onSourceChanged(map: MapAdapter, sourceId: String?) {
        if (sourceId == null) styleState.refreshSources() else styleState.refreshSource(sourceId)
      }

      override fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) {
        if (cameraState.map !== map) return
        cameraState.moveReasonState.value = reason
        cameraState.isCameraMovingState.value = true
      }

      override fun onCameraMoved(map: MapAdapter) {
        if (cameraState.map !== map) return
        cameraState.positionState.value = map.getCameraPosition()
        // A new instance so a composition that reads CameraState.viewport redraws when the
        // transform changes without the camera position changing, which is what a resize does.
        cameraState.viewportState.value = map.getViewport()
      }

      override fun onCameraMoveEnded(map: MapAdapter) {
        if (cameraState.map !== map) return
        cameraState.isCameraMovingState.value = false
      }

      /** Offers the click to each layer that has a [handlerOf] handler, topmost first. */
      private fun routeClick(
        map: MapAdapter,
        offset: DpOffset,
        handlerOf: (ClickRoute) -> FeaturesClickHandler?,
      ) {
        // The host publishes the routing snapshot after each sync; reading only the snapshot
        // keeps this off the mutable node tree the host owns.
        clickScope?.launch {
          for (route in styleNode.clickRoutes) {
            if (handlerOf(route) == null) continue
            val features =
              map.queryRenderedFeatures(
                offset = offset,
                layerIds = setOf(route.layerId),
                predicate = null,
              )
            // Recomposition may replace or remove the layer while the query is suspended. A
            // removed layer never receives the click; a replaced one answers with the handler
            // the latest snapshot has.
            val currentHandle =
              styleNode.clickRoutes.firstOrNull { it.layerId == route.layerId }?.let(handlerOf)
                ?: continue
            if (features.isNotEmpty() && currentHandle(features).consumed) break
          }
        }
      }

      override fun onClick(map: MapAdapter, latLng: Position, offset: DpOffset) {
        if (onMapClick(latLng, offset).consumed) return
        routeClick(map, offset) { it.onClick }
      }

      override fun onLongClick(map: MapAdapter, latLng: Position, offset: DpOffset) {
        if (onMapLongClick(latLng, offset).consumed) return
        routeClick(map, offset) { it.onLongClick }
      }

      override fun onFrame(fps: Double) {
        this@MapState.onFrame(fps)
      }
    }
}
