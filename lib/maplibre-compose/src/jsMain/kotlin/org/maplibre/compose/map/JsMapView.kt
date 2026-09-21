package org.maplibre.compose.map

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import org.maplibre.compose.gljs.GlJsMapSurface
import org.maplibre.compose.interaction.internal.FeatureClickDispatcher

@Composable internal actual fun mapPresentationHostIdentity(): Any = Unit

@Composable
private fun PlatformMapView(
  modifier: Modifier,
  state: MapState,
  session: GlJsMapSession,
  clicks: FeatureClickDispatcher,
  options: MapViewOptions,
) {
  val logger = state.runtime.logger
  BindMapInput(session, clicks) { engaged ->
    state.attachmentAuthority.setEngaged(session, engaged)
  }

  // A new Canvas delays the first frame until the update path attaches the camera to the session.
  key(session) {
    GlJsMapSurface(
      renderer = session,
      modifier =
        modifier.background(
          if (session.canPresentFrames) Color.Transparent else options.uiOptions.loadColor
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
