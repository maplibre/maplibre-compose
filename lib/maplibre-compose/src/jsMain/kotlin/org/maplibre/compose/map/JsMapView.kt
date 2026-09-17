package org.maplibre.compose.map

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import org.maplibre.compose.gljs.GlJsMapSurface
import org.maplibre.compose.interaction.internal.FeatureClickDispatcher
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
  session: GlJsMapSession,
  clicks: FeatureClickDispatcher,
  options: MapViewOptions,
) {
  val density = LocalDensity.current
  val logger = state.runtime.logger

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
            clicks::capture,
            clicks::hasHandlers,
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
    GlJsMapPresentation(state, presentationOwner, options) { session, clicks ->
      PlatformMapView(modifier, state, session, clicks, options)
    }
  }
}
