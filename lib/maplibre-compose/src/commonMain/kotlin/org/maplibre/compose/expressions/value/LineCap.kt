package org.maplibre.compose.expressions.value

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline

/**
 * Display of line endings
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
@JvmInline
public value class LineCap private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<LineCap> {
    /** A cap with a squared-off end which is drawn to the exact endpoint of the line. */
    public val Butt: LineCap = LineCap("butt")

    /**
     * A cap with a rounded end which is drawn beyond the endpoint of the line at a radius of
     * one-half of the line's width and centered on the endpoint of the line.
     */
    public val Round: LineCap = LineCap("round")

    /**
     * A cap with a squared-off end which is drawn beyond the endpoint of the line at a distance of
     * one-half of the line's width.
     */
    public val Square: LineCap = LineCap("square")

    public override val entries: List<LineCap> = listOf(Butt, Round, Square)
  }
}
