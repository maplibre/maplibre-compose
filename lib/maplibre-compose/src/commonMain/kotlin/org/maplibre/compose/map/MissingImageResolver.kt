package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import org.maplibre.compose.style.renderPainter
import org.maplibre.compose.util.ImageStretch

/**
 * Supplies the image that the engine asks for by id, or null when the resolver has none.
 *
 * See [MapState.missingImageResolver].
 */
public typealias MissingImageResolver = suspend (id: String) -> ResolvedStyleImage?

/**
 * The image that a [MissingImageResolver] supplies, with the options that [StyleImages.add] takes
 * for it.
 */
@Immutable
public data class ResolvedStyleImage(
  /** The pixels that the engine draws. */
  public val image: ImageBitmap,
  /** Whether [image] is a signed distance field, which a layer recolors. */
  public val sdf: Boolean = false,
  /** Stretch and content box for an icon that a symbol layer sizes to wrap its text. */
  public val stretch: ImageStretch? = null,
) {
  public companion object {
    /**
     * Renders [painter] once for a missing-image resolver or another imperative image operation.
     *
     * [density] and [layoutDirection] describe the environment in which the painter draws. In
     * Compose, pass `LocalDensity.current` and `LocalLayoutDirection.current` from the caller's
     * composition. Rendering uses a standalone graphics context and releases it before returning.
     *
     * [size] is in DP. When omitted, the painter's intrinsic pixel size is used, falling back to 16
     * by 16 DP. [drawAsSdf] converts the rendered pixels to a signed distance field for monochrome
     * icons. [alpha] and [colorFilter] are passed to [Painter.draw]. Changes to the painter after
     * this call do not update the result.
     *
     * @throws IllegalArgumentException If the size has a non-positive dimension.
     */
    public suspend fun fromPainter(
      painter: Painter,
      density: Density,
      layoutDirection: LayoutDirection,
      size: DpSize? = null,
      drawAsSdf: Boolean = false,
      stretch: ImageStretch? = null,
      alpha: Float = DefaultAlpha,
      colorFilter: ColorFilter? = null,
    ): ResolvedStyleImage = withImageGraphicsContext { graphicsContext ->
      ResolvedStyleImage(
        image =
          renderPainter(
            painter,
            graphicsContext,
            density,
            layoutDirection,
            size,
            drawAsSdf,
            alpha,
            colorFilter,
          ),
        sdf = drawAsSdf,
        stretch = stretch,
      )
    }
  }
}
