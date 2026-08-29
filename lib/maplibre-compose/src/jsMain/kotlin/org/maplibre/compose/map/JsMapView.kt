package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import org.maplibre.compose.gljs.GlJsMapSurface
import org.maplibre.compose.util.rememberAbandonable

@Composable
internal actual fun ComposableMapView(state: MapState, modifier: Modifier, options: MapOptions) {
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  val scaleFactor = density.density.toDouble()
  val logger = state.logger

  val engine = state.engine
  val resource =
    rememberAbandonable(
      scaleFactor,
      onAbandoned = { it.release() },
      create = {
        GlJsSessionResource(
          engine = engine,
          session =
            GlJsMapSession(
              callbacks = state.callbacks,
              logger = logger,
              layoutDirection = layoutDirection,
            ),
        )
      },
    )
  val session = resource.session

  session.callbacks = state.callbacks
  session.logger = logger

  MapSessionHost(
    resource = resource,
    state = state,
    attach = { s ->
      // bindMapSession skips this when the state is closed; keep the session-closed guard for a
      // session the engine refused before attach ran.
      if (!state.isClosed && !s.isClosed) state.attachSession(s)
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

/** The composable's claim on the engine: the session constructed eagerly, registered at attach. */
private class GlJsSessionResource(
  private val engine: MapEngine,
  override val session: GlJsMapSession,
) : MapSessionResource<GlJsMapSession> {
  override fun register() {
    engine.registerSession(session)
  }

  override fun release() {
    session.close()
    engine.releaseSession(session)
  }
}
