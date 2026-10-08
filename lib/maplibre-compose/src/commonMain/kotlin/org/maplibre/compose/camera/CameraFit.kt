package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import org.maplibre.compose.util.DpPadding

/**
 * The camera orientation and padding used to fit bounds or positions in the viewport.
 *
 * @param bearing Direction that the camera points in, in degrees clockwise from north.
 * @param pitch Camera angle in degrees from directly down.
 * @param cameraPadding Padding of the resulting camera. Null retains the current camera padding.
 * @param fitPadding Temporary margin inside the viewport insets and camera padding.
 */
@Immutable
public data class CameraFit(
  public val bearing: Double = 0.0,
  public val pitch: Double = 0.0,
  public val cameraPadding: DpPadding? = null,
  public val fitPadding: DpPadding = DpPadding.Zero,
)
