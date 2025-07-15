package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.StringLiteral

/** In combination with [SymbolPlacement], determines the rotation behavior of icons. */
public enum class IconRotationAlignment(override val literal: StringLiteral) :
  EnumValue<IconRotationAlignment> {
  /**
   * For [SymbolPlacement.Point], aligns icons east-west. Otherwise, aligns icon x-axes with the
   * line.
   */
  Map(StringLiteral.of("map")),

  /**
   * Produces icons whose x-axes are aligned with the x-axis of the viewport, regardless of the
   * [SymbolPlacement].
   */
  Viewport(StringLiteral.of("viewport")),

  /**
   * For [SymbolPlacement.Point], this is equivalent to [IconRotationAlignment.Viewport]. Otherwise,
   * this is equivalent to [IconRotationAlignment.Map].
   */
  Auto(StringLiteral.of("auto")),
}
