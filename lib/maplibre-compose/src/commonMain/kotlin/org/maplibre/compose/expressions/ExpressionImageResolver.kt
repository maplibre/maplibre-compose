package org.maplibre.compose.expressions

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter as ComposePainter
import androidx.compose.ui.unit.DpSize
import org.maplibre.compose.util.ImageStretch

/** Supplies image IDs during [toStyleJson]; it does not register images with a map. */
public fun interface ExpressionImageResolver {
  /** Returns a style image ID for [reference], or `null` if it cannot be resolved. */
  public fun resolve(reference: ExpressionImageReference): String?
}

/**
 * An image reference encountered during expression export, including its rendering inputs.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface ExpressionImageReference {
  /** Whether the image uses a signed distance field. */
  public val isSdf: Boolean
  /** Stretch regions and content box, or `null` for an unstretched image. */
  public val stretch: ImageStretch?

  /** A bitmap reference. [bitmap] is the original bitmap; exporting never reads its pixels. */
  public data class Bitmap
  internal constructor(
    public val bitmap: ImageBitmap,
    override val isSdf: Boolean,
    override val stretch: ImageStretch?,
  ) : ExpressionImageReference

  /**
   * A painter reference. [painter] is the original painter; exporting never draws it. [size] is the
   * requested DP size, or `null` to use the normal intrinsic-size/default rules. [alpha] and
   * [colorFilter] are the inputs supplied to the image expression.
   */
  public data class Painter
  internal constructor(
    public val painter: ComposePainter,
    public val size: DpSize?,
    override val isSdf: Boolean,
    override val stretch: ImageStretch?,
    public val alpha: Float,
    public val colorFilter: ColorFilter?,
  ) : ExpressionImageReference
}

internal object UnspecifiedExpressionImageReference : ExpressionImageReference {
  override val isSdf = false
  override val stretch: ImageStretch? = null
}
