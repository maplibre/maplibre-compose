package org.maplibre.compose.map

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

  fun onStyleReady(engine: EngineMapIdentity, style: StyleIdentity, map: MapAdapter) =
    withStyle(engine, style) {
      delegate().onStyleReady(map)
    }

  fun onStyleFailed(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
    map: MapAdapter,
    reason: String?,
    beforeDelegate: () -> Unit = {},
  ) =
    lifecycle.acceptStyleRequestEvent(engine, request) {
      beforeDelegate()
      delegate().onStyleFailed(map, reason)
    }

  fun onStyleSourcesChanged(
    engine: EngineMapIdentity,
    style: StyleIdentity,
    map: MapAdapter,
    sourceId: String?,
  ) =
    withStyle(engine, style) {
      delegate().onStyleSourcesChanged(map, sourceId)
    }

  fun onGestureActive(
    engine: EngineMapIdentity,
    lease: RenderLease,
    map: MapAdapter,
    active: Boolean,
  ) =
    withPresentation(engine, lease) {
      delegate().onGestureActive(map, active)
    }

  fun onViewportChanged(engine: EngineMapIdentity, lease: RenderLease, map: MapAdapter) =
    withPresentation(engine, lease) {
      delegate().onViewportChanged(map)
    }

  fun onEvent(engine: EngineMapIdentity, map: MapAdapter, event: MapEvent) =
    lifecycle.acceptEngineEvent(engine) { delegate().onEvent(map, event) }

  /**
   * [beforeDelegate] runs inside the same acceptance, so a session can publish the viewport the
   * event describes before the delegate reads it.
   */
  fun onEvent(
    engine: EngineMapIdentity,
    lease: RenderLease,
    map: MapAdapter,
    event: MapEvent,
    beforeDelegate: () -> Unit = {},
  ) =
    withPresentation(engine, lease) {
      beforeDelegate()
      delegate().onEvent(map, event)
    }

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
