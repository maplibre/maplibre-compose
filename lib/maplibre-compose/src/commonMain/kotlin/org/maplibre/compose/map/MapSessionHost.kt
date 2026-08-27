package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester

/**
 * The session lifecycle every map view shares: attach on launch, release then detach on dispose,
 * and the focus and gesture wiring handed to [content]. The session type is platform-specific, so
 * the platform passes its attach and release steps as lambdas.
 */
@Composable
internal fun <S : Any> MapSessionHost(
  session: S,
  state: MapState,
  attach: (S, MapState) -> Unit,
  release: (S) -> Unit,
  content: @Composable (FocusRequester, GestureContinuation) -> Unit,
) {
  // Captured at session creation: a later composition may pass another state, and the session must
  // attach and detach the state that owns it.
  val sessionState = remember(session) { state }

  LaunchedEffect(session) { attach(session, sessionState) }

  DisposableEffect(session) {
    onDispose {
      release(session)
      sessionState.detachSession()
    }
  }

  val focusRequester = remember { FocusRequester() }
  val inputScope = rememberCoroutineScope()
  val continuation = remember(session, inputScope) { GestureContinuation(inputScope) }
  content(focusRequester, continuation)
}
