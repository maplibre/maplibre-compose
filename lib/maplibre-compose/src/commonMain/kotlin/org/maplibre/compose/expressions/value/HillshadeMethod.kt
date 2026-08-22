package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.StringLiteral

/** The hillshade algorithm used to shade a DEM. */
public enum class HillshadeMethod(override val literal: StringLiteral) :
  EnumValue<HillshadeMethod> {

  /** The legacy hillshade method. */
  Standard(StringLiteral.of("standard")),

  /**
   * Basic hillshade. Uses a simple physics model where the reflected light intensity is
   * proportional to the cosine of the angle between the incident light and the surface normal.
   * Similar to GDAL's `gdaldem` default algorithm.
   */
  Basic(StringLiteral.of("basic")),

  /** Hillshade whose intensity scales with slope. Similar to GDAL's `gdaldem` with `-combined`. */
  Combined(StringLiteral.of("combined")),

  /**
   * Hillshade that tries to minimize effects on other map features beneath. Similar to GDAL's
   * `gdaldem` with `-igor`.
   */
  Igor(StringLiteral.of("igor")),

  /**
   * Hillshade with multiple illumination directions. Uses the basic hillshade model with multiple
   * independent light sources.
   */
  Multidirectional(StringLiteral.of("multidirectional")),
}
