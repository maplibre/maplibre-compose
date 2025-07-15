package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.StringLiteral

/** Orientation of circles when the map is pitched. */
public enum class CirclePitchAlignment(override val literal: StringLiteral) :
  EnumValue<CirclePitchAlignment> {
  /** Circles are aligned to the plane of the map, i.e. flat on top of the map. */
  Map(StringLiteral.of("map")),

  /** Circles are aligned to the plane of the viewport, i.e. facing the camera. */
  Viewport(StringLiteral.of("viewport")),
}
