package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/**
 * Symbol placement relative to its geometry.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class SymbolPlacement private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<SymbolPlacement> {
    /** The label is placed at the point where the geometry is located. */
    public val Point: SymbolPlacement = SymbolPlacement("point")

    /**
     * The label is placed along the line of the geometry. Can only be used on LineString and
     * Polygon geometries.
     */
    public val Line: SymbolPlacement = SymbolPlacement("line")

    /**
     * The label is placed at the center of the line of the geometry. Can only be used on LineString
     * and Polygon geometries. Note that a single feature in a vector tile may contain multiple line
     * geometries.
     */
    public val LineCenter: SymbolPlacement = SymbolPlacement("line-center")

    public override val entries: List<SymbolPlacement> = listOf(Point, Line, LineCenter)
  }
}
