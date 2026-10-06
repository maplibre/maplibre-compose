package org.maplibre.compose.expressions.value

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline

/**
 * The hillshade algorithm used to shade a DEM.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
@JvmInline
public value class HillshadeMethod private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<HillshadeMethod> {
    /** The legacy hillshade method. */
    public val Standard: HillshadeMethod = HillshadeMethod("standard")

    /**
     * Basic hillshade. Uses a simple physics model where the reflected light intensity is
     * proportional to the cosine of the angle between the incident light and the surface normal.
     * Similar to GDAL's `gdaldem` default algorithm.
     */
    public val Basic: HillshadeMethod = HillshadeMethod("basic")

    /**
     * Hillshade whose intensity scales with slope. Similar to GDAL's `gdaldem` with `-combined`.
     */
    public val Combined: HillshadeMethod = HillshadeMethod("combined")

    /**
     * Hillshade that tries to minimize effects on other map features beneath. Similar to GDAL's
     * `gdaldem` with `-igor`.
     */
    public val Igor: HillshadeMethod = HillshadeMethod("igor")

    /**
     * Hillshade with multiple illumination directions. Uses the basic hillshade model with multiple
     * independent light sources.
     */
    public val Multidirectional: HillshadeMethod = HillshadeMethod("multidirectional")

    public override val entries: List<HillshadeMethod> =
      listOf(Standard, Basic, Combined, Igor, Multidirectional)
  }
}
