package org.maplibre.compose.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/** Collects the map configuration that the platform view receives as one internal value. */
@Immutable
internal data class MapViewOptions(
  val cameraPadding: PaddingValues = PaddingValues(0.dp),
  val cameraConstraints: CameraConstraints = CameraConstraints(),
  val renderOptions: RenderOptions = RenderOptions.Standard,
  val gestureOptions: GestureOptions = GestureOptions.Standard,
  val tileLodOptions: TileLodOptions = TileLodOptions.Standard,
)
