package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.StringLiteral

/** A named map projection. See [Projection][org.maplibre.compose.style.Projection]. */
public enum class ProjectionType(override val literal: StringLiteral) :
  EnumValue<ProjectionType>, ProjectionValue {

  /** The Web Mercator projection, which draws the map as a flat plane. */
  Mercator(StringLiteral.of("mercator")),

  /** A globe seen from a camera at a finite distance, at every zoom level. */
  VerticalPerspective(StringLiteral.of("vertical-perspective")),

  /**
   * A globe that becomes a flat map when zoomed in: [VerticalPerspective] up to zoom 11, blending
   * into [Mercator] by zoom 12.
   */
  Globe(StringLiteral.of("globe")),
}
