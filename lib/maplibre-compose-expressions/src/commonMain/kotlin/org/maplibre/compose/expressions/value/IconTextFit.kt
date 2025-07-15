package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.StringLiteral

/** Scales the icon to fit around the associated text. */
public enum class IconTextFit(override val literal: StringLiteral) : EnumValue<IconTextFit> {
  /** The icon is displayed at its intrinsic aspect ratio. */
  None(StringLiteral.of("none")),

  /** The icon is scaled in the x-dimension to fit the width of the text. */
  Width(StringLiteral.of("width")),

  /** The icon is scaled in the y-dimension to fit the height of the text. */
  Height(StringLiteral.of("height")),

  /** The icon is scaled in both x- and y-dimensions. */
  Both(StringLiteral.of("both")),
}
