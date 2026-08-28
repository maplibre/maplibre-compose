package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import org.maplibre.compose.gljs.GlJsMapSurface

@Composable
internal actual fun ComposableMapView(state: MapState, modifier: Modifier, options: MapOptions) {
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  val scaleFactor = density.density.toDouble()
  val logger = state.logger

  val engine = state.engine
  val session =
    remember(scaleFactor) {
        // An abandoned composition runs no DisposableEffect; only onAbandoned can release this.
        // The engine learns of the session in the attach effect, after the composition applies.
        object : RememberObserver {
          val session =
            GlJsMapSession(
              callbacks = state.callbacks,
              logger = logger,
              layoutDirection = layoutDirection,
            )

          override fun onRemembered() {}

          override fun onForgotten() {}

          override fun onAbandoned() {
            session.close()
          }
        }
      }
      .session

  session.callbacks = state.callbacks
  session.logger = logger
  session.layoutDirection = layoutDirection

  MapSessionHost(
    session = session,
    state = state,
    attach = { s ->
      engine.registerSession(s)
      // A session the closed engine refused must not attach; the closed state would throw.
      if (!s.isClosed) state.attachSession(s)
    },
    release = {
      it.close()
      engine.releaseSession(it)
    },
  ) { focusRequester, continuation ->
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
}
