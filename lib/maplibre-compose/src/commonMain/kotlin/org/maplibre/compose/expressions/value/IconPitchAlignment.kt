package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.StringLiteral

/** Orientation of icon when map is pitched. */
public enum class IconPitchAlignment(override val literal: StringLiteral) :
  EnumValue<IconPitchAlignment> {
  /** The icon is aligned to the plane of the map. */
  Map(StringLiteral.of("map")),

  /** The icon is aligned to the plane of the viewport, i.e. as if glued to the screen */
  Viewport(StringLiteral.of("viewport")),

  /** Automatically matches the value of [IconRotationAlignment] */
  Auto(StringLiteral.of("auto")),
}
