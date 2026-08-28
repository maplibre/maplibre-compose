package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.style.ClickRoute
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MapClickHandler
import org.maplibre.spatialk.geojson.Position

/**
 * Forwards platform callbacks into [MapState]'s kernel as generation-tagged events. The kernel
 * decides whether each event still belongs to current state.
 */
internal class MapStateCallbacks(private val state: MapState) : MapAdapter.Callbacks {

  @Volatile var onMapClick: MapClickHandler = { _, _ -> ClickResult.Pass }
  @Volatile var onMapLongClick: MapClickHandler = { _, _ -> ClickResult.Pass }
  @Volatile var onFrame: (framesPerSecond: Double) -> Unit = {}
  @Volatile var onMapLoadFailed: (reason: String?) -> Unit = {}
  @Volatile var onMapLoadFinished: () -> Unit = {}

  /** The scope click queries launch on. Null drops clicks; only a missing UI leaves it null. */
  @Volatile var clickScope: CoroutineScope? = null

  /** Detach resets the hooks so a retained core's later events reach no disposed composable. */
  fun resetSessionHooks() {
    onMapClick = { _, _ -> ClickResult.Pass }
    onMapLongClick = { _, _ -> ClickResult.Pass }
    onFrame = {}
    onMapLoadFailed = {}
    onMapLoadFinished = {}
    clickScope = null
  }

  override fun onMapDestroyed(map: MapAdapter) {
    state.commit { mapDestroyed(map) }
  }

  override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
    onStyleChanged(map, style, 0L)
  }

  override fun onStyleChanged(map: MapAdapter, style: StyleBinding?, styleGeneration: Long) {
    state.commit { styleChanged(map, style, styleGeneration) }
  }

  override fun onMapFailLoading(reason: String?) {
    onMapFailLoading(reason, 0L)
  }

  override fun onMapFailLoading(reason: String?, styleGeneration: Long) {
    val source = state.attachedAdapter ?: state.engine.detachedAdapter
    state.commit {
      styleLoadFailed(source, styleGeneration, reason ?: "MapLibre failed to load the map")
    }
  }

  override fun onMapFinishedLoading(map: MapAdapter) {
    onMapFinishedLoading(map, 0L)
  }

  override fun onMapFinishedLoading(map: MapAdapter, styleGeneration: Long) {
    state.commit { styleLoadFinished(map, styleGeneration) }
  }

  override fun onSourceChanged(map: MapAdapter, sourceId: String?) {
    if (sourceId == null) state.sources.refreshSources() else state.sources.refreshSource(sourceId)
    state.styleNode.refreshLiveLayerIds()
  }

  override fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) {
    state.commit { cameraMoveStarted(map, reason) }
  }

  override fun onCameraMoved(map: MapAdapter) {
    val position = map.getCameraPosition()
    val viewport = map.getViewport()
    state.commit { cameraMoved(map, position, viewport) }
  }

  override fun onCameraMoveEnded(map: MapAdapter) {
    state.commit { cameraMoveEnded(map) }
  }

  override fun onSurfaceLost(map: MapAdapter) {
    state.commit { surfaceLost(map, 0L) }
  }

  /** Offers the click to each layer that has a [handlerOf] handler, topmost first. */
  private fun routeClick(
    map: MapAdapter,
    offset: DpOffset,
    handlerOf: (ClickRoute) -> FeaturesClickHandler?,
  ) {
    clickScope?.launch {
      for (route in state.styleNode.clickRoutes) {
        if (handlerOf(route) == null) continue
        val features =
          map.queryRenderedFeatures(
            offset = offset,
            layerIds = setOf(route.layerId),
            predicate = null,
          )
        val currentHandle =
          state.styleNode.clickRoutes.firstOrNull { it.layerId == route.layerId }?.let(handlerOf)
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
    onFrame.invoke(fps)
  }
}
