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
import org.maplibre.compose.util.PreparedImage
import org.maplibre.compose.util.prepareInEngineContext

/**
 * Supplies the image that the engine asks for by id, or null when the resolver has none.
 *
 * See [MapState.missingImageResolver].
 */
public typealias MissingImageResolver = suspend (id: String) -> ResolvedStyleImage?

/**
 * A [PreparedImage] with style-image options. It belongs to no map; each command targets the style
 * loaded when submitted.
 */
@Immutable
public class ResolvedStyleImage(
  /** The image's pixels. */
  public val image: PreparedImage,
  /** Whether the pixels form a signed distance field, which a layer recolors. */
  public val sdf: Boolean = false,
  /** Stretch and content box for an icon that a symbol layer sizes to wrap its text. */
  public val stretch: ImageStretch? = null,
) {
  override fun equals(other: Any?): Boolean =
    other is ResolvedStyleImage &&
      image == other.image &&
      sdf == other.sdf &&
      stretch == other.stretch

  override fun hashCode(): Int =
    31 * (31 * image.hashCode() + sdf.hashCode()) + (stretch?.hashCode() ?: 0)

  public companion object {
    /**
     * Copies [image] into a [PreparedImage] on the calling thread, so prepare large images off the
     * main thread. Later changes to [image] do not change the result. Keep and reuse the result
     * while the image is unchanged to avoid preparing another pixel copy.
     *
     * [sdf] indicates that the pixels already form a signed distance field; it does not convert
     * them. [stretch] defines the stretch and content box for an icon that wraps its text.
     *
     * @throws IllegalArgumentException if [image] has a zero width or height.
     */
    public fun fromBitmap(
      image: ImageBitmap,
      sdf: Boolean = false,
      stretch: ImageStretch? = null,
    ): ResolvedStyleImage = ResolvedStyleImage(PreparedImage.fromBitmap(image), sdf, stretch)

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
          prepareInEngineContext(
            renderPainter(
              painter,
              graphicsContext,
              density,
              layoutDirection,
              size,
              drawAsSdf,
              alpha,
              colorFilter,
            )
          ),
        sdf = drawAsSdf,
        stretch = stretch,
      )
    }
  }
}
