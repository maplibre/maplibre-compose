package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/**
 * Direction of light source when map is rotated.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class IlluminationAnchor private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<IlluminationAnchor> {
    /** The hillshade illumination is relative to the north direction. */
    public val Map: IlluminationAnchor = IlluminationAnchor("map")

    /** The hillshade illumination is relative to the top of the viewport. */
    public val Viewport: IlluminationAnchor = IlluminationAnchor("viewport")

    public override val entries: List<IlluminationAnchor> = listOf(Map, Viewport)
  }
}
