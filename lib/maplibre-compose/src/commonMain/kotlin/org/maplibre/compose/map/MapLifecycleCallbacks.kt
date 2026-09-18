package org.maplibre.compose.map

import kotlinx.coroutines.Deferred
import org.maplibre.compose.style.StyleBinding

/**
 * Filters platform callbacks through identities captured by their platform producer, then posts
 * each delegate call to the main dispatcher. A `beforeDelegate` still runs inside the acceptance on
 * the calling thread. [resolveMissingImage] answers the engine synchronously.
 */
internal class MapLifecycleCallbacks(
  private val lifecycle: MapLifecycleBinding,
  private val delegate: () -> MapAdapter.Callbacks,
) {

  fun beginStyleRequest(engine: EngineMapIdentity, map: MapAdapter): StyleRequestIdentity? {
    val request = lifecycle.claimStyleRequestIdentity(engine) ?: return null
    lifecycle.acceptStyleRequestEvent(engine, request) {
      lifecycle.postToMain { delegate().onStyleChanged(map, null) }
    }
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
      lifecycle.postToMain { delegate().onStyleChanged(map, style) }
    }
  }

  fun onStyleReady(engine: EngineMapIdentity, style: StyleIdentity, map: MapAdapter) =
    lifecycle.acceptStyleEvent(engine, style) {
      lifecycle.postToMain { delegate().onStyleReady(map) }
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
      lifecycle.postToMain { delegate().onStyleFailed(map, reason) }
    }

  fun onStyleSourcesChanged(
    engine: EngineMapIdentity,
    style: StyleIdentity,
    map: MapAdapter,
    sourceId: String?,
  ) =
    lifecycle.acceptStyleEvent(engine, style) {
      lifecycle.postToMain { delegate().onStyleSourcesChanged(map, sourceId) }
    }

  fun onGestureActive(
    engine: EngineMapIdentity,
    lease: RenderLease,
    map: MapAdapter,
    active: Boolean,
  ) =
    lifecycle.acceptPresentationEvent(engine, lease) {
      lifecycle.postToMain { delegate().onGestureActive(map, active) }
    }

  fun onViewportChanged(engine: EngineMapIdentity, lease: RenderLease, map: MapAdapter) =
    lifecycle.acceptPresentationEvent(engine, lease) {
      lifecycle.postToMain { delegate().onViewportChanged(map) }
    }

  fun onEvent(engine: EngineMapIdentity, map: MapAdapter, event: MapEvent) =
    lifecycle.acceptEngineEvent(engine) { lifecycle.postToMain { delegate().onEvent(map, event) } }

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
    lifecycle.acceptPresentationEvent(engine, lease) {
      beforeDelegate()
      lifecycle.postToMain { delegate().onEvent(map, event) }
    }

  fun onEvent(engine: EngineMapIdentity, style: StyleIdentity, map: MapAdapter, event: MapEvent) =
    lifecycle.acceptStyleEvent(engine, style) {
      lifecycle.postToMain { delegate().onEvent(map, event) }
    }

  fun onEvent(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
    map: MapAdapter,
    event: MapEvent,
  ) =
    lifecycle.acceptStyleRequestEvent(engine, request) {
      lifecycle.postToMain { delegate().onEvent(map, event) }
    }

  /** Asks the loaded style's owner to supply a missing image. */
  fun resolveMissingImage(
    engine: EngineMapIdentity,
    style: StyleIdentity,
    map: MapAdapter,
    imageId: String,
  ): Deferred<Unit>? {
    var resolution: Deferred<Unit>? = null
    lifecycle.acceptStyleEvent(engine, style) {
      resolution = delegate().resolveMissingImage(map, imageId)
    }
    return resolution
  }

  fun onPresentationEvent(engine: EngineMapIdentity, lease: RenderLease, event: () -> Unit) =
    lifecycle.acceptPresentationEvent(engine, lease, event)
}
