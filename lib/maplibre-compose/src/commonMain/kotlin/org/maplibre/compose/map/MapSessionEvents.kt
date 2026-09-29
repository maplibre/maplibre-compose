package org.maplibre.compose.map

import kotlin.jvm.JvmInline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleIdentity
import org.maplibre.compose.style.StyleRequestId

/** Identifies one platform engine-map instance until its destruction. */
@JvmInline internal value class EngineMapIdentity(private val value: Long)

/** Identifies one temporary attachment of a logical map to a presentation. */
@JvmInline internal value class RenderLease(private val value: Long)

/** The stamps on engine reports that one session still accepts. Main thread only. */
internal interface SessionStamps {
  /** Whether [engine] is the session's engine map and the session is open. */
  fun isCurrentEngine(engine: EngineMapIdentity): Boolean

  /** Whether [lease] is the attached presentation of [engine]. */
  fun isCurrentPresentation(engine: EngineMapIdentity, lease: RenderLease): Boolean

  /** Whether [request] is the latest base-style request. */
  fun isCurrentStyleRequest(request: StyleRequestId): Boolean

  /** Whether [style] loaded for the latest base-style request. */
  fun isCurrentStyle(style: StyleIdentity): Boolean
}

/**
 * Delivers one session's engine reports to the map state on the main thread.
 *
 * Each report carries the stamp its producer captured, and reaches [callbacks] only if [stamps]
 * still accepts that stamp when the post runs. The check is made at delivery because a post from
 * the main thread runs inline and can overtake one that an engine thread queued earlier. Callable
 * from any thread.
 */
internal class MapSessionEvents(
  private val map: MapAdapter,
  private val stamps: SessionStamps,
  private val postToMain: (() -> Unit) -> Unit,
  private val callbacks: () -> MapAdapter.Callbacks,
) {
  /** Reports that [request] replaced the loaded style, which leaves no binding current. */
  fun styleRequested(request: StyleRequestId) =
    deliver({ stamps.isCurrentStyleRequest(request) }) { callbacks().onStyleChanged(map, null) }

  fun styleLoaded(style: StyleBinding) =
    deliver({ stamps.isCurrentStyle(style.identity) }) { callbacks().onStyleChanged(map, style) }

  fun styleReady(style: StyleIdentity) =
    deliver({ stamps.isCurrentStyle(style) }) { callbacks().onStyleReady(map) }

  fun styleFailed(request: StyleRequestId, reason: String?) =
    deliver({ stamps.isCurrentStyleRequest(request) }) { callbacks().onStyleFailed(map, reason) }

  fun styleSourcesChanged(style: StyleIdentity, sourceId: String?) =
    deliver({ stamps.isCurrentStyle(style) }) {
      callbacks().onStyleSourcesChanged(map, sourceId)
    }

  fun engineEvent(engine: EngineMapIdentity, event: MapEvent) =
    deliver({ stamps.isCurrentEngine(engine) }) { callbacks().onEvent(map, event) }

  fun presentationEvent(engine: EngineMapIdentity, lease: RenderLease, event: MapEvent) =
    deliver({ stamps.isCurrentPresentation(engine, lease) }) { callbacks().onEvent(map, event) }

  fun styleEvent(style: StyleIdentity, event: MapEvent) =
    deliver({ stamps.isCurrentStyle(style) }) { callbacks().onEvent(map, event) }

  fun styleRequestEvent(request: StyleRequestId, event: MapEvent) =
    deliver({ stamps.isCurrentStyleRequest(request) }) { callbacks().onEvent(map, event) }

  fun gestureActive(engine: EngineMapIdentity, lease: RenderLease, active: Boolean) =
    deliver({ stamps.isCurrentPresentation(engine, lease) }) {
      callbacks().onGestureActive(map, active)
    }

  fun viewportChanged(engine: EngineMapIdentity, lease: RenderLease) =
    deliver({ stamps.isCurrentPresentation(engine, lease) }) { callbacks().onViewportChanged(map) }

  /**
   * Asks the loaded style's owner to supply a missing image. The answer always completes: at once
   * when [style] is no longer current, and otherwise once the owner has supplied the image or
   * declined to.
   */
  fun resolveMissingImage(style: StyleIdentity, imageId: String): Deferred<Unit> {
    val answer = CompletableDeferred<Unit>()
    postToMain {
      if (!stamps.isCurrentStyle(style)) {
        answer.complete(Unit)
        return@postToMain
      }
      val resolution = callbacks().resolveMissingImage(map, imageId)
      if (resolution == null) answer.complete(Unit)
      else resolution.invokeOnCompletion { answer.complete(Unit) }
    }
    return answer
  }

  private inline fun deliver(crossinline accepts: () -> Boolean, crossinline event: () -> Unit) =
    postToMain {
      if (accepts()) event()
    }
}
