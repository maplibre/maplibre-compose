package org.maplibre.compose.interaction.internal

import org.maplibre.compose.camera.internal.CameraInputTarget
import org.maplibre.compose.interaction.MapInteractions

/**
 * The live camera target and click path of one presented [org.maplibre.compose.map.MapState], for
 * Compose input that does not own the renderer.
 */
internal class MapInputAttachment(
  val target: CameraInputTarget,
  val captureClickPath: (TapFamily) -> ClickPath?,
  val hasClickHandlers: (TapFamily) -> Boolean,
  val interactions: () -> MapInteractions,
  val setEngaged: (Boolean) -> Unit,
)
