package org.maplibre.compose.map

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import org.maplibre.compose.gljs.GlJsMapSurface

@Composable internal actual fun mapPresentationHostIdentity(): Any = Unit

@Composable
internal actual fun rememberComposeMapPresentation(
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
): ComposeMapPresentation? {
  // Replacing a density-specific engine renews its reservation before disposing the old one.
  return key(LocalDensity.current.density) {
    GlJsMapPresentation(state, presentationOwner, options) { session, clicks ->
      ComposeMapPresentation(
        session,
        clicks,
        { state.attachmentAuthority.setEngaged(session, it) },
      ) { modifier ->
        GlJsMapSurface(
          renderer = session,
          modifier =
            modifier.background(
              if (session.canPresentFrames) Color.Transparent else options.uiOptions.loadColor
            ),
          logger = state.runtime.logger,
          presentFrames = session.canPresentFrames,
        )
      }
    }
  }
}
