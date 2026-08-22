package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import co.touchlab.kermit.Logger
import org.maplibre.compose.gljs.GlJsMapSurface
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.SafeStyle

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  style: BaseStyle,
  rememberedStyle: SafeStyle?,
  update: (map: MapAdapter) -> Unit,
  onReset: () -> Unit,
  logger: Logger?,
  callbacks: MapAdapter.Callbacks,
  options: MapOptions,
) {
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
  val scaleFactor = density.density.toDouble()

  val session =
    remember(scaleFactor) {
      GlJsMapSession(callbacks = callbacks, logger = logger, layoutDirection = layoutDirection)
    }

  session.callbacks = callbacks
  session.logger = logger
  session.layoutDirection = layoutDirection
  val currentOnReset = rememberUpdatedState(onReset)

  // Must run in the apply phase, not from a coroutine: the unload has to precede the content
  // subcomposition inserting layers, or a style switch crashes on anchor validation (see #269).
  SideEffect { session.setBaseStyle(style) }

  LaunchedEffect(session, options, update) { update(session) }

  DisposableEffect(session) {
    onDispose {
      session.close()
      currentOnReset.value()
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
      presentFrames = rememberedStyle != null,
    )
  }
}
