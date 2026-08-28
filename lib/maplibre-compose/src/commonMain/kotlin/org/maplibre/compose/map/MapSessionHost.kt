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

  /** The engine transition; [MapSessionHost] runs it after the composition applies. */
  fun register()

  /** Releases the session and its engine claims; safe unregistered, and on abandonment. */
  fun release()
}

/**
 * The session lifecycle every map view shares: register and attach on launch, release then detach
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
    resource.register()
    attach(resource.session)
    attached[0] = true
  }

  DisposableEffect(resource) {
    onDispose {
      resource.release()
      if (attached[0]) state.detachSession()
    }
  }

  val focusRequester = remember { FocusRequester() }
  val inputScope = rememberCoroutineScope()
  val continuation = remember(resource, inputScope) { GestureContinuation(inputScope) }
  content(focusRequester, continuation)
}
