package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.StringLiteral

/** Display of line endings */
public enum class LineCap(override val literal: StringLiteral) : EnumValue<LineCap> {
  /** A cap with a squared-off end which is drawn to the exact endpoint of the line. */
  Butt(StringLiteral.of("butt")),

  /**
   * A cap with a rounded end which is drawn beyond the endpoint of the line at a radius of one-half
   * of the line's width and centered on the endpoint of the line.
   */
  Round(StringLiteral.of("round")),

  /**
   * A cap with a squared-off end which is drawn beyond the endpoint of the line at a distance of
   * one-half of the line's width.
   */
  Square(StringLiteral.of("square")),
}
