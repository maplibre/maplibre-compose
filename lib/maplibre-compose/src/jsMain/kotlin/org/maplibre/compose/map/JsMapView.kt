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
import co.touchlab.kermit.Logger
import org.maplibre.compose.gljs.GlJsMapSurface

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  engine: MapEngine,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapOptions,
) {
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  val scaleFactor = density.density.toDouble()

  // Keyed on the engine so swapping the composable's state tears the session down with its map.
  val mapEngine = engine as GlJsMapEngine
  val session =
    remember(mapEngine, scaleFactor) {
      GlJsMapSession(callbacks = callbacks, logger = logger, layoutDirection = layoutDirection)
        .also { mapEngine.session = it }
    }

  session.callbacks = callbacks
  session.logger = logger
  session.layoutDirection = layoutDirection

  // Captured at session creation: a later composition may pass another state's detach, and the
  // dying session must detach the state that owns it.
  val sessionOnReset = remember(session) { onReset }

  LaunchedEffect(session, options, update) { update(session) }

  DisposableEffect(session) {
    onDispose {
      session.close()
      if (mapEngine.session === session) mapEngine.session = null
      sessionOnReset()
    }
  }

  val focusRequester = remember { FocusRequester() }
  val inputScope = rememberCoroutineScope()
  val continuation = remember(session, inputScope) { GestureContinuation(inputScope) }

  // A new Canvas delays the first frame until the update path attaches the camera to the session.
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
