package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.interaction.internal.FeatureClickDispatcher

/** Identifies the platform presentation host that owns the current UI surface. */
@Composable internal expect fun mapPresentationHostIdentity(): Any

/** Creates the presentation before composing its input and surface. Null means it has closed. */
@Composable
internal expect fun rememberComposeMapPresentation(
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
): ComposeMapPresentation?

internal class ComposeMapPresentation(
  val target: CameraInputTarget,
  val clicks: FeatureClickDispatcher,
  val onEngagedChanged: (Boolean) -> Unit,
  val surface: @Composable (Modifier) -> Unit,
)
