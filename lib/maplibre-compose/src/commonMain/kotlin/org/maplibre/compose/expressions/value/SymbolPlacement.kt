package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.StringLiteral

/** Symbol placement relative to its geometry. */
public enum class SymbolPlacement(override val literal: StringLiteral) :
  EnumValue<SymbolPlacement> {
  /** The label is placed at the point where the geometry is located. */
  Point(StringLiteral.of("point")),

  /**
   * The label is placed along the line of the geometry. Can only be used on LineString and Polygon
   * geometries.
   */
  Line(StringLiteral.of("line")),

  /**
   * The label is placed at the center of the line of the geometry. Can only be used on LineString
   * and Polygon geometries. Note that a single feature in a vector tile may contain multiple line
   * geometries.
   */
  LineCenter(StringLiteral.of("line-center")),
}
