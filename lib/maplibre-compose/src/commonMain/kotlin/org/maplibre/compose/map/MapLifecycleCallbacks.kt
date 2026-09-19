package org.maplibre.compose.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.maplibre.compose.style.StyleBinding

/**
 * Filters platform callbacks through identities captured by their platform producer, then posts
 * each delegate call to the main dispatcher. The identity is checked again when the posted call
 * runs, so a style or presentation replaced in between drops the stale delivery. A `beforeDelegate`
 * still runs inside the first acceptance on the calling thread.
 */
internal class MapLifecycleCallbacks(
  private val lifecycle: MapLifecycleBinding,
  private val delegate: () -> MapAdapter.Callbacks,
) {

  fun beginStyleRequest(engine: EngineMapIdentity, map: MapAdapter): StyleRequestIdentity? {
    val request = lifecycle.claimStyleRequestIdentity(engine) ?: return null
    postStyleRequestEvent(engine, request) { delegate().onStyleChanged(map, null) }
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
      lifecycle.postToMain {
        lifecycle.acceptStyleEvent(engine, identity) { delegate().onStyleChanged(map, style) }
      }
    }
  }

  fun onStyleReady(engine: EngineMapIdentity, style: StyleIdentity, map: MapAdapter) =
    postStyleEvent(engine, style) { delegate().onStyleReady(map) }

  fun onStyleFailed(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
    map: MapAdapter,
    reason: String?,
    beforeDelegate: () -> Unit = {},
  ) =
    lifecycle.acceptStyleRequestEvent(engine, request) {
      beforeDelegate()
      lifecycle.postToMain {
        lifecycle.acceptStyleRequestEvent(engine, request) { delegate().onStyleFailed(map, reason) }
      }
    }

  fun onStyleSourcesChanged(
    engine: EngineMapIdentity,
    style: StyleIdentity,
    map: MapAdapter,
    sourceId: String?,
  ) = postStyleEvent(engine, style) { delegate().onStyleSourcesChanged(map, sourceId) }

  fun onGestureActive(
    engine: EngineMapIdentity,
    lease: RenderLease,
    map: MapAdapter,
    active: Boolean,
  ) = postPresentationEvent(engine, lease) { delegate().onGestureActive(map, active) }

  fun onViewportChanged(engine: EngineMapIdentity, lease: RenderLease, map: MapAdapter) =
    postPresentationEvent(engine, lease) { delegate().onViewportChanged(map) }

  fun onEvent(engine: EngineMapIdentity, map: MapAdapter, event: MapEvent) =
    lifecycle.acceptEngineEvent(engine) {
      lifecycle.postToMain {
        lifecycle.acceptEngineEvent(engine) { delegate().onEvent(map, event) }
      }
    }

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
      lifecycle.postToMain {
        lifecycle.acceptPresentationEvent(engine, lease) { delegate().onEvent(map, event) }
      }
    }

  fun onEvent(engine: EngineMapIdentity, style: StyleIdentity, map: MapAdapter, event: MapEvent) =
    postStyleEvent(engine, style) { delegate().onEvent(map, event) }

  fun onEvent(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
    map: MapAdapter,
    event: MapEvent,
  ) = postStyleRequestEvent(engine, request) { delegate().onEvent(map, event) }

  /**
   * Asks the loaded style's owner to supply a missing image. The answer completes once the owner,
   * on the main dispatcher, has either supplied the image or declined to. Null means the style is
   * no longer current.
   */
  fun resolveMissingImage(
    engine: EngineMapIdentity,
    style: StyleIdentity,
    map: MapAdapter,
    imageId: String,
  ): Deferred<Unit>? {
    val answer = CompletableDeferred<Unit>()
    val accepted =
      lifecycle.acceptStyleEvent(engine, style) {
        lifecycle.postToMain {
          var resolution: Deferred<Unit>? = null
          lifecycle.acceptStyleEvent(engine, style) {
            resolution = delegate().resolveMissingImage(map, imageId)
          }
          val pending = resolution
          if (pending == null) answer.complete(Unit)
          else pending.invokeOnCompletion { answer.complete(Unit) }
        }
      }
    return if (accepted) answer else null
  }

  fun onPresentationEvent(engine: EngineMapIdentity, lease: RenderLease, event: () -> Unit) =
    lifecycle.acceptPresentationEvent(engine, lease, event)

  private fun postStyleEvent(engine: EngineMapIdentity, style: StyleIdentity, event: () -> Unit) =
    lifecycle.acceptStyleEvent(engine, style) {
      lifecycle.postToMain { lifecycle.acceptStyleEvent(engine, style, event) }
    }

  private fun postStyleRequestEvent(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
    event: () -> Unit,
  ) =
    lifecycle.acceptStyleRequestEvent(engine, request) {
      lifecycle.postToMain { lifecycle.acceptStyleRequestEvent(engine, request, event) }
    }

  private fun postPresentationEvent(
    engine: EngineMapIdentity,
    lease: RenderLease,
    event: () -> Unit,
  ) =
    lifecycle.acceptPresentationEvent(engine, lease) {
      lifecycle.postToMain { lifecycle.acceptPresentationEvent(engine, lease, event) }
    }
}
