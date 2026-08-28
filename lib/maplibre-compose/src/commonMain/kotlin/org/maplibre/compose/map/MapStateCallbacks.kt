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
 * The session callbacks of one [MapState]: they write the state's records and invoke the hooks that
 * the [MaplibreMap] showing the state supplies.
 */
internal class MapStateCallbacks(private val state: MapState) : MapAdapter.Callbacks {

  // A UI SideEffect writes these hooks and the map's owner and renderer threads read them, so
  // Volatile supplies the only happens-before edge between the write and those reads.
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

  override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
    if (style != null) state.lastLoadFailure.value = null
    state.updateBinding(style)
    if (state.attachedAdapter === map) state.viewportState.value = map.getViewport()
  }

  override fun onMapFailLoading(reason: String?) {
    state.lastLoadFailure.value = reason ?: "MapLibre failed to load the map"
    // A retained map can fail a load between sessions; the next attach replays the report.
    if (state.attachedAdapter == null) {
      state.loadFailedWhileDetached = reason ?: "MapLibre failed to load the map"
      return
    }
    onMapLoadFailed(reason)
  }

  override fun onMapFinishedLoading(map: MapAdapter) {
    state.refreshStyleCollections()
    // A retained map can finish a load between sessions; the next attach replays the report.
    if (state.attachedAdapter == null) {
      state.loadFinishedWhileDetached = true
      return
    }
    onMapLoadFinished()
  }

  override fun onSourceChanged(map: MapAdapter, sourceId: String?) {
    if (sourceId == null) state.sources.refreshSources() else state.sources.refreshSource(sourceId)
    state.styleNode.refreshLiveLayerIds()
  }

  override fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) {
    if (state.attachedAdapter !== map) return
    state.moveReasonState.value = reason
    state.isCameraMovingState.value = true
  }

  override fun onCameraMoved(map: MapAdapter) {
    if (state.attachedAdapter !== map) return
    state.positionState.value = map.getCameraPosition()
    // A new instance so a composition that reads MapState.viewport redraws when the transform
    // changes without the camera position changing, which is what a resize does.
    state.viewportState.value = map.getViewport()
  }

  override fun onCameraMoveEnded(map: MapAdapter) {
    if (state.attachedAdapter !== map) return
    state.isCameraMovingState.value = false
  }

  /** Offers the click to each layer that has a [handlerOf] handler, topmost first. */
  private fun routeClick(
    map: MapAdapter,
    offset: DpOffset,
    handlerOf: (ClickRoute) -> FeaturesClickHandler?,
  ) {
    // The host publishes the routing snapshot after each sync; reading only the snapshot keeps this
    // off the mutable node tree the host owns.
    clickScope?.launch {
      for (route in state.styleNode.clickRoutes) {
        if (handlerOf(route) == null) continue
        val features =
          map.queryRenderedFeatures(
            offset = offset,
            layerIds = setOf(route.layerId),
            predicate = null,
          )
        // Recomposition may replace or remove the layer while the query is suspended. A removed
        // layer never receives the click; a replaced one answers with the handler the latest
        // snapshot has.
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

  // invoke, because the property and this override share a name.
  override fun onFrame(fps: Double) {
    onFrame.invoke(fps)
  }
}
