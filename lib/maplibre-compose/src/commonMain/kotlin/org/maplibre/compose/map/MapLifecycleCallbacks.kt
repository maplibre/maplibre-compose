package org.maplibre.compose.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.maplibre.compose.style.StyleBinding

/**
 * Delivers platform callbacks to the map state on the main dispatcher.
 *
 * Each callback carries the identity its platform producer captured. The binding accepts it once on
 * the calling thread, where work such as publishing a viewport belongs, then posts the delegate
 * call to the main dispatcher and accepts it again when it runs. The second check exists because a
 * post made from the main thread runs inline and can overtake a post that an engine thread queued
 * earlier, so a queued delivery may find its style or presentation replaced.
 */
internal class MapLifecycleCallbacks(
  private val lifecycle: MapLifecycleBinding,
  private val delegate: () -> MapAdapter.Callbacks,
) {

  fun beginStyleRequest(engine: EngineMapIdentity, map: MapAdapter): StyleRequestIdentity? {
    val request = lifecycle.claimStyleRequestIdentity(engine) ?: return null
    deliver(styleRequest(engine, request)) { delegate().onStyleChanged(map, null) }
    return request
  }

  fun onStyleChanged(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
    map: MapAdapter,
    style: StyleBinding,
    beforeDelegate: (StyleIdentity) -> Unit = {},
  ): StyleIdentity? =
    lifecycle.claimStyleIdentity(engine, request) { identity ->
      beforeDelegate(identity)
      lifecycle.postToMain {
        lifecycle.acceptStyleEvent(engine, identity) { delegate().onStyleChanged(map, style) }
      }
    }

  fun onStyleReady(engine: EngineMapIdentity, style: StyleIdentity, map: MapAdapter) =
    deliver(styleEvent(engine, style)) { delegate().onStyleReady(map) }

  fun onStyleFailed(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
    map: MapAdapter,
    reason: String?,
    beforeDelegate: () -> Unit = {},
  ) =
    deliver(styleRequest(engine, request), beforeDelegate) { delegate().onStyleFailed(map, reason) }

  fun onStyleSourcesChanged(
    engine: EngineMapIdentity,
    style: StyleIdentity,
    map: MapAdapter,
    sourceId: String?,
  ) = deliver(styleEvent(engine, style)) { delegate().onStyleSourcesChanged(map, sourceId) }

  fun onGestureActive(
    engine: EngineMapIdentity,
    lease: RenderLease,
    map: MapAdapter,
    active: Boolean,
  ) = deliver(presentation(engine, lease)) { delegate().onGestureActive(map, active) }

  fun onViewportChanged(engine: EngineMapIdentity, lease: RenderLease, map: MapAdapter) =
    deliver(presentation(engine, lease)) { delegate().onViewportChanged(map) }

  fun onEvent(engine: EngineMapIdentity, map: MapAdapter, event: MapEvent) =
    deliver(engineEvent(engine)) { delegate().onEvent(map, event) }

  /** [beforeDelegate] runs inside the first acceptance, before the event is posted. */
  fun onEvent(
    engine: EngineMapIdentity,
    lease: RenderLease,
    map: MapAdapter,
    event: MapEvent,
    beforeDelegate: () -> Unit = {},
  ) = deliver(presentation(engine, lease), beforeDelegate) { delegate().onEvent(map, event) }

  fun onEvent(engine: EngineMapIdentity, style: StyleIdentity, map: MapAdapter, event: MapEvent) =
    deliver(styleEvent(engine, style)) { delegate().onEvent(map, event) }

  fun onEvent(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
    map: MapAdapter,
    event: MapEvent,
  ) = deliver(styleRequest(engine, request)) { delegate().onEvent(map, event) }

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
      deliver(styleEvent(engine, style)) {
        val resolution = delegate().resolveMissingImage(map, imageId)
        if (resolution == null) answer.complete(Unit)
        else resolution.invokeOnCompletion { answer.complete(Unit) }
      }
    return if (accepted) answer else null
  }

  fun onPresentationEvent(engine: EngineMapIdentity, lease: RenderLease, event: () -> Unit) =
    lifecycle.acceptPresentationEvent(engine, lease, event)

  /** An acceptance: runs its block only while the captured identity is still current. */
  private fun interface Acceptance {
    fun accept(block: () -> Unit): Boolean
  }

  private fun styleEvent(engine: EngineMapIdentity, style: StyleIdentity) = Acceptance {
    lifecycle.acceptStyleEvent(engine, style, it)
  }

  private fun styleRequest(engine: EngineMapIdentity, request: StyleRequestIdentity) = Acceptance {
    lifecycle.acceptStyleRequestEvent(engine, request, it)
  }

  private fun presentation(engine: EngineMapIdentity, lease: RenderLease) = Acceptance {
    lifecycle.acceptPresentationEvent(engine, lease, it)
  }

  private fun engineEvent(engine: EngineMapIdentity) = Acceptance {
    lifecycle.acceptEngineEvent(engine, it)
  }

  /** Accepts now, posts, and accepts again when the post runs. Returns the first acceptance. */
  private fun deliver(
    acceptance: Acceptance,
    beforeDelegate: () -> Unit = {},
    event: () -> Unit,
  ): Boolean = acceptance.accept {
    beforeDelegate()
    lifecycle.postToMain { acceptance.accept(event) }
  }
}
