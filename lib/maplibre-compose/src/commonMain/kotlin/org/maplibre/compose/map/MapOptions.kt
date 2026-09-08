package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp
import org.maplibre.compose.interaction.MapInteractions

/** Shared presentation settings, independent of Compose UI hosting. */
@Immutable
internal data class MapViewOptions(
  val cameraPadding: PaddingValues = PaddingValues(0.dp),
  val cameraConstraints: CameraConstraints = CameraConstraints(),
  val renderOptions: RenderOptions = RenderOptions.Standard,
  val interactions: MapInteractions = MapInteractions.Standard,
  val uiOptions: MapUiOptions = MapUiOptions.Standard,
)
