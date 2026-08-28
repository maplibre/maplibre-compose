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
  attach: (S) -> Unit,
  release: (S) -> Unit,
  content: @Composable (FocusRequester, GestureContinuation) -> Unit,
) {
  LaunchedEffect(session) { attach(session) }

  DisposableEffect(session) {
    onDispose {
      release(session)
      state.detachSession()
    }
  }

  val focusRequester = remember { FocusRequester() }
  val inputScope = rememberCoroutineScope()
  val continuation = remember(session, inputScope) { GestureContinuation(inputScope) }
  content(focusRequester, continuation)
}
