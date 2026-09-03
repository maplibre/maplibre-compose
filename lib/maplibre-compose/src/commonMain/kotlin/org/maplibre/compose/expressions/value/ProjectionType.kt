package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.StringLiteral

/** A named map projection. See [Projection][org.maplibre.compose.style.Projection]. */
public enum class ProjectionType(override val literal: StringLiteral) :
  EnumValue<ProjectionType>, ProjectionValue {

  /** The Web Mercator projection. */
  Mercator(StringLiteral.of("mercator")),

  /** A globe projection at every zoom level. */
  VerticalPerspective(StringLiteral.of("vertical-perspective")),

  /** [VerticalPerspective] below zoom 11, interpolating to [Mercator] by zoom 12. */
  Globe(StringLiteral.of("globe")),
}
