package org.maplibre.compose.map

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import org.maplibre.compose.gljs.GlJsMapSurface
import org.maplibre.compose.interaction.internal.InputConfiguration
import org.maplibre.compose.interaction.internal.InputFocus
import org.maplibre.compose.interaction.internal.inputEnvironment
import org.maplibre.compose.interaction.internal.mapInput
import org.maplibre.compose.interaction.internal.rotaryNotchPixels

@Composable internal actual fun mapPresentationHostIdentity(): Any = Unit

@Composable
private fun PlatformMapView(
  modifier: Modifier,
  state: MapState,
  binding: MapPresentationBinding,
  options: MapViewOptions,
) {
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current

  val callbacks = binding.callbacks
  val logger = state.runtime.logger
  val session = remember {
    GlJsMapSession(
      lifecycleAuthority = state.lifecycle,
      callbacks = callbacks,
      logger = logger,
      layoutDirection = layoutDirection,
      requests = state.runtime.jsRequests,
    )
  }

  session.callbacks = callbacks
  session.logger = logger
  session.layoutDirection = layoutDirection
  val currentOnReset = rememberUpdatedState(binding.onReset)

  // Must run in the apply phase, not from a coroutine: the unload has to precede the content
  // subcomposition inserting layers, or a style switch inserts them against the base style being
  // replaced (see #269).
  SideEffect { session.setBaseStyle(state.style.baseStyle) }
  if (session.hasUsableViewport) {
    SideEffect {
      binding.update(session)
      session.markPresentationStateReplayed()
    }
  }

  LaunchedEffect(session) { session.start() }

  DisposableEffect(session) {
    onDispose {
      session.close()
      currentOnReset.value()
    }
  }

  val focusRequester = remember { FocusRequester() }
  val inputFocus =
    remember(session, state) {
      InputFocus { engaged -> state.setEngaged(session, engaged) }
    }
  // A press can engage the map before the attachment publishes, and a write before that is
  // dropped.
  val attached = state.currentMapAttachment?.adapter === session
  LaunchedEffect(inputFocus, attached) { if (attached) inputFocus.replay() }
  val inputEnvironment = inputEnvironment()
  val rotaryNotchPixels = rotaryNotchPixels()

  // A new Canvas delays the first frame until the update path attaches the camera to the session.
  key(session) {
    GlJsMapSurface(
      renderer = session,
      modifier =
        modifier
          .background(
            if (session.canPresentFrames) Color.Transparent else options.uiOptions.loadColor
          )
          .indication(inputFocus.indicationInteractions, inputEnvironment.indication)
          .mapInput(
            session,
            binding.clicks::capture,
            binding.clicks::hasHandlers,
            InputConfiguration(options.interactions, options.uiOptions.bindings),
            density,
            focusRequester,
            inputFocus,
            inputEnvironment,
            rotaryNotchPixels,
          ),
      logger = logger,
      presentFrames = session.canPresentFrames,
    )
  }
}

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
) {
  // The browser destroys its engine when this density-specific presentation is replaced. Renew
  // the reservation with the session so the old session's disposal cannot release the new one.
  key(LocalDensity.current.density) {
    MapPresentationContent(state, presentationOwner, options) { binding ->
      PlatformMapView(modifier, state, binding, options)
    }
  }
}
