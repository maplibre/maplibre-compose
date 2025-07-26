package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Represents configuration options for rendering behavior in a map view. This includes options such
 * as the type of rendered surface, debug settings, and performance-specific parameters.
 *
 * @param renderMode Determines the type of graphical surface to use for rendering. Options include
 *   [RenderMode.SurfaceView] for higher performance or [RenderMode.TextureView] for improved
 *   compatibility with certain transformations, at a significant performance cost.
 * @param foregroundLoadColor Sets the color displayed in the foreground while the map is
 *   initializing.
 * @param isDebugEnabled Sets whether debug overlays are shown.
 * @param maximumFps Specifies the maximum frame rate for rendering the map view. The value is
 *   limited by the device's hardware capabilities.
 */
@Immutable
public actual data class RenderOptions(
  val renderMode: RenderMode = RenderMode.SurfaceView,
  val foregroundLoadColor: Color = Color.Transparent,
  val isDebugEnabled: Boolean = false,
  val maximumFps: Int? = null,
) {
  public actual companion object Companion {
    public actual val Standard: RenderOptions = RenderOptions()
    public actual val Debug: RenderOptions = RenderOptions(isDebugEnabled = true)
  }

  public enum class RenderMode {
    SurfaceView,
    TextureView,
  }
}
