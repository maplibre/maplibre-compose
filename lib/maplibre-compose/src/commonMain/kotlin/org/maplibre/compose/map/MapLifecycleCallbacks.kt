package org.maplibre.compose.map

import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.style.StyleBinding

/** Filters platform callbacks through identities captured by their platform producer. */
internal class MapLifecycleCallbacks(
  private val lifecycle: MapLifecycleBinding,
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
    beforeDelegate: (StyleIdentity) -> Unit = {},
  ): StyleIdentity? {
    return lifecycle.claimStyleIdentity(engine, request) { identity ->
      beforeDelegate(identity)
      delegate().onStyleChanged(map, style)
    }
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
    map: MapAdapter,
    reason: String?,
    beforeDelegate: () -> Unit = {},
  ) =
    lifecycle.acceptStyleRequestEvent(engine, request) {
      beforeDelegate()
      delegate().onMapFailLoading(map, reason)
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

  fun onFrame(engine: EngineMapIdentity, lease: RenderLease, fps: Double) =
    withPresentation(engine, lease) { delegate().onFrame(fps) }

  fun onEvent(engine: EngineMapIdentity, map: MapAdapter, event: MapEvent) =
    lifecycle.acceptEngineEvent(engine) { delegate().onEvent(map, event) }

  fun onEvent(engine: EngineMapIdentity, lease: RenderLease, map: MapAdapter, event: MapEvent) =
    withPresentation(engine, lease) { delegate().onEvent(map, event) }

  fun onEvent(engine: EngineMapIdentity, style: StyleIdentity, map: MapAdapter, event: MapEvent) =
    withStyle(engine, style) { delegate().onEvent(map, event) }

  fun onEvent(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
    map: MapAdapter,
    event: MapEvent,
  ) = lifecycle.acceptStyleRequestEvent(engine, request) { delegate().onEvent(map, event) }

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
