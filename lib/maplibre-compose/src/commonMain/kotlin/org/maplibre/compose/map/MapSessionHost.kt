package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester

/**
 * A composition-owned session over a map engine. The remember initializer constructs it without
 * touching the engine; [register] is the engine transition, and [release] returns everything,
 * whether or not [register] ever ran.
 */
internal interface MapSessionResource<S : Any> {
  val session: S

  /** The engine transition; [MapSessionHost] runs it after the record accepts the session. */
  fun register()

  /** Releases the session and its engine claims; safe unregistered, and on abandonment. */
  fun release()
}

/**
 * The session lifecycle every map view shares: attach and register on launch, release then detach
 * on dispose, and the focus and gesture wiring handed to [content].
 */
@Composable
internal fun <S : Any> MapSessionHost(
  resource: MapSessionResource<S>,
  state: MapState,
  attach: (S) -> Unit,
  content: @Composable (FocusRequester, GestureContinuation) -> Unit,
) {
  // A rejected rival session must not detach the state that another session attached.
  val attached = remember(resource) { arrayOf(false) }
  LaunchedEffect(resource) {
    bindMapSession(resource, state, attach)
    attached[0] = true
  }

  DisposableEffect(resource) {
    onDispose {
      // The detach precedes the release, so a load that finishes mid-release buffers for the next
      // session instead of reaching the departing composable's hooks.
      if (attached[0]) state.detachSession()
      resource.release()
    }
  }

  val focusRequester = remember { FocusRequester() }
  val inputScope = rememberCoroutineScope()
  val continuation = remember(resource, inputScope) { GestureContinuation(inputScope) }
  content(focusRequester, continuation)
}

/**
 * The record accepts the session first. The engine is registered only after that, so a capture
 * lease or a closed state cannot leave the engine holding a session the record refused. A failure
 * after this call attached a previously empty record detaches that session. A rival that fails
 * register against a core the record already held leaves that attachment in place.
 * [MapSessionResource.release] stays with dispose.
 */
internal fun <S : Any> bindMapSession(
  resource: MapSessionResource<S>,
  state: MapState,
  attach: (S) -> Unit,
) {
  if (state.isClosed) {
    // The record refuses every session. Register so the engine closes a still-composed view's
    // fresh session and logs the refusal; attach would throw into the composition.
    resource.register()
    return
  }
  val alreadyAttached = state.attachedAdapter
  var bound = false
  try {
    attach(resource.session)
    bound = true
    resource.register()
  } catch (error: Throwable) {
    // Native attach uses the shared core as the adapter. A rival that fails register must not
    // detach the session that already held that core.
    if (bound && alreadyAttached == null) state.detachSession()
    throw error
  }
}
