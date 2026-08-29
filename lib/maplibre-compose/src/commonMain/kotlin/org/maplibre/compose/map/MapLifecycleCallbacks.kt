package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.style.StyleBinding
import org.maplibre.spatialk.geojson.Position

/** Filters platform callbacks through identities captured by their platform producer. */
internal class MapLifecycleCallbacks(
  private val lifecycle: MapLifecycleAuthority,
  private val delegate: () -> MapAdapter.Callbacks,
) {

  fun beginStyleRequest(engine: EngineMapIdentity, map: MapAdapter): StyleRequestIdentity? {
    val request = lifecycle.claimStyleRequestIdentity(engine) ?: return null
    lifecycle.acceptStyleRequestEvent(engine, request) { delegate().onStyleChanged(map, null) }
    return request
  }

  fun onStyleChanged(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
    map: MapAdapter,
    style: StyleBinding,
  ): StyleIdentity? {
    return lifecycle.claimStyleIdentity(engine, request) { delegate().onStyleChanged(map, style) }
  }

  fun onMapFinishedLoading(engine: EngineMapIdentity, style: StyleIdentity, map: MapAdapter) =
    withStyle(engine, style) {
      delegate().onMapFinishedLoading(map)
    }

  fun onSourceChanged(
    engine: EngineMapIdentity,
    style: StyleIdentity,
    map: MapAdapter,
    sourceId: String?,
  ) =
    withStyle(engine, style) {
      delegate().onSourceChanged(map, sourceId)
    }

  fun onMapFailLoading(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
    reason: String?,
  ) =
    lifecycle.acceptStyleRequestEvent(engine, request) {
      delegate().onMapFailLoading(reason)
    }

  fun onCameraMoveStarted(
    engine: EngineMapIdentity,
    lease: RenderLease,
    map: MapAdapter,
    reason: CameraMoveReason,
  ) =
    withPresentation(engine, lease) {
      delegate().onCameraMoveStarted(map, reason)
    }

  fun onCameraMoved(
    engine: EngineMapIdentity,
    lease: RenderLease,
    map: MapAdapter,
    beforeDelegate: () -> Unit = {},
  ) =
    withPresentation(engine, lease) {
      beforeDelegate()
      delegate().onCameraMoved(map)
    }

  fun onCameraMoveEnded(
    engine: EngineMapIdentity,
    lease: RenderLease,
    map: MapAdapter,
    afterDelegate: () -> Unit = {},
  ) =
    withPresentation(engine, lease) {
      try {
        delegate().onCameraMoveEnded(map)
      } finally {
        afterDelegate()
      }
    }

  fun onClick(
    engine: EngineMapIdentity,
    lease: RenderLease,
    map: MapAdapter,
    latLng: Position,
    offset: DpOffset,
  ) =
    withPresentation(engine, lease) {
      delegate().onClick(map, latLng, offset)
    }

  fun onLongClick(
    engine: EngineMapIdentity,
    lease: RenderLease,
    map: MapAdapter,
    latLng: Position,
    offset: DpOffset,
  ) =
    withPresentation(engine, lease) {
      delegate().onLongClick(map, latLng, offset)
    }

  fun onFrame(engine: EngineMapIdentity, lease: RenderLease, fps: Double) =
    withPresentation(engine, lease) { delegate().onFrame(fps) }

  fun onPresentationEvent(engine: EngineMapIdentity, lease: RenderLease, event: () -> Unit) =
    withPresentation(engine, lease, event)

  private fun withStyle(
    engine: EngineMapIdentity,
    style: StyleIdentity,
    event: () -> Unit,
  ): Boolean = lifecycle.acceptStyleEvent(engine, style, event)

  private fun withPresentation(
    engine: EngineMapIdentity,
    lease: RenderLease,
    event: () -> Unit,
  ): Boolean = lifecycle.acceptPresentationEvent(engine, lease, event)
}
