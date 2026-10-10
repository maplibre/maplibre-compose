package org.maplibre.compose.camera

import androidx.compose.runtime.Immutable
import org.maplibre.compose.util.DpPadding

/**
 * The camera orientation and padding used to fit bounds or positions in the viewport.
 *
 * @property bearing Direction that the camera points in, in degrees clockwise from north.
 * @property pitch Camera angle in degrees from directly down.
 * @property cameraPadding Padding of the resulting camera. Null retains the current camera padding.
 * @property fitPadding Temporary margin inside the viewport insets and camera padding.
 */
@Immutable
public data class CameraFit(
  public val bearing: Double = 0.0,
  public val pitch: Double = 0.0,
  public val cameraPadding: DpPadding? = null,
  public val fitPadding: DpPadding = DpPadding.Zero,
)
