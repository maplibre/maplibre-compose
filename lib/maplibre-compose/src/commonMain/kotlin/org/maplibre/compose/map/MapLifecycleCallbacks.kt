package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.style.StyleBinding
import org.maplibre.spatialk.geojson.Position

/** Filters platform callbacks through the identities owned by [lifecycle]. */
internal class MapLifecycleCallbacks(
  private val lifecycle: MapLifecycleAuthority,
  private val delegate: () -> MapAdapter.Callbacks,
) : MapAdapter.Callbacks {

  override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
    val engine = lifecycle.engineIdentity ?: return
    if (style == null) {
      if (lifecycle.invalidateStyleIdentity(engine)) {
        lifecycle.acceptEngineEvent(engine) { delegate().onStyleChanged(map, null) }
      }
      return
    }
    val identity = lifecycle.claimStyleIdentity(engine) ?: return
    lifecycle.acceptStyleEvent(engine, identity) { delegate().onStyleChanged(map, style) }
  }

  override fun onMapFinishedLoading(map: MapAdapter) = withStyle {
    delegate().onMapFinishedLoading(map)
  }

  override fun onSourceChanged(map: MapAdapter, sourceId: String?) = withStyle {
    delegate().onSourceChanged(map, sourceId)
  }

  override fun onMapFailLoading(reason: String?) = withEngine {
    delegate().onMapFailLoading(reason)
  }

  override fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) = withPresentation {
    delegate().onCameraMoveStarted(map, reason)
  }

  override fun onCameraMoved(map: MapAdapter) = withPresentation {
    delegate().onCameraMoved(map)
  }

  override fun onCameraMoveEnded(map: MapAdapter) = withPresentation {
    delegate().onCameraMoveEnded(map)
  }

  override fun onClick(map: MapAdapter, latLng: Position, offset: DpOffset) = withPresentation {
    delegate().onClick(map, latLng, offset)
  }

  override fun onLongClick(map: MapAdapter, latLng: Position, offset: DpOffset) = withPresentation {
    delegate().onLongClick(map, latLng, offset)
  }

  override fun onFrame(fps: Double) = withPresentation { delegate().onFrame(fps) }

  private fun withEngine(event: () -> Unit) {
    val engine = lifecycle.engineIdentity ?: return
    lifecycle.acceptEngineEvent(engine, event)
  }

  private fun withStyle(event: () -> Unit) {
    val engine = lifecycle.engineIdentity ?: return
    val style = lifecycle.styleIdentity ?: return
    lifecycle.acceptStyleEvent(engine, style, event)
  }

  private fun withPresentation(event: () -> Unit) {
    val engine = lifecycle.engineIdentity ?: return
    val lease = lifecycle.renderLease ?: return
    lifecycle.acceptPresentationEvent(engine, lease, event)
  }
}
