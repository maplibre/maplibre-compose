package org.maplibre.compose.style.expressions.value

import org.maplibre.compose.style.expressions.ast.StringLiteral

/** Orientation of text when map is pitched. */
public enum class TextPitchAlignment(override val literal: StringLiteral) :
  EnumValue<TextPitchAlignment> {
  /** The text is aligned to the plane of the map. */
  Map(StringLiteral.of("map")),

  /** The text is aligned to the plane of the viewport, i.e. as if glued to the screen */
  Viewport(StringLiteral.of("viewport")),

  /** Automatically matches the value of [TextRotationAlignment] */
  Auto(StringLiteral.of("auto")),
}
