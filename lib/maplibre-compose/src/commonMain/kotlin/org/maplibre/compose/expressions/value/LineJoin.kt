package org.maplibre.compose.expressions.value

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline

/**
 * Display of joined lines
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
@JvmInline
public value class LineJoin private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<LineJoin> {
    /**
     * A join with a squared-off end which is drawn beyond the endpoint of the line at a distance of
     * one-half of the line's width.
     */
    public val Bevel: LineJoin = LineJoin("bevel")

    /**
     * A join with a rounded end which is drawn beyond the endpoint of the line at a radius of
     * one-half of the line's width and centered on the endpoint of the line.
     */
    public val Round: LineJoin = LineJoin("round")

    /**
     * A join with a sharp, angled corner which is drawn with the outer sides beyond the endpoint of
     * the path until they meet.
     */
    public val Miter: LineJoin = LineJoin("miter")

    public override val entries: List<LineJoin> = listOf(Bevel, Round, Miter)
  }
}
