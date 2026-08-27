package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import org.maplibre.compose.gljs.GlJsMapSurface

@Composable
internal actual fun ComposableMapView(state: MapState, modifier: Modifier, options: MapOptions) {
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  val scaleFactor = density.density.toDouble()
  val logger = state.logger

  // Keyed on the engine so swapping the composable's state tears the session down with its map.
  val engine = state.engine
  val session =
    remember(engine, scaleFactor) {
      GlJsMapSession(
          callbacks = state.callbacks,
          logger = logger,
          layoutDirection = layoutDirection,
        )
        .also { engine.registerSession(it) }
    }

  session.callbacks = state.callbacks
  session.logger = logger
  session.layoutDirection = layoutDirection

  // Captured at session creation: a later composition may pass another state, and the session must
  // attach and detach the state that owns it.
  val sessionState = remember(session) { state }

  // A session the closed engine refused must not attach; the closed state would throw.
  LaunchedEffect(session) { if (!session.isClosed) sessionState.attachSession(session) }

  DisposableEffect(session) {
    onDispose {
      session.close()
      engine.releaseSession(session)
      sessionState.detachSession()
    }
  }

  val focusRequester = remember { FocusRequester() }
  val inputScope = rememberCoroutineScope()
  val continuation = remember(session, inputScope) { GestureContinuation(inputScope) }

  // A new Canvas delays the first frame until the attach path wires the camera to the session.
  key(session) {
    GlJsMapSurface(
      renderer = session,
      modifier =
        modifier.mapInput(session, options.gestureOptions, density, focusRequester, continuation),
      logger = logger,
      presentFrames = session.hasLoadedFirstStyle,
    )
  }
}
