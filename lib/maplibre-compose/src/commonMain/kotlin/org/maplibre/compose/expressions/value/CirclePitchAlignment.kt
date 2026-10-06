package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/**
 * Orientation of circles when the map is pitched.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class CirclePitchAlignment private constructor(override val value: String) :
  EnumValue {
  public companion object : EnumType<CirclePitchAlignment> {
    /** Circles are aligned to the plane of the map, i.e. flat on top of the map. */
    public val Map: CirclePitchAlignment = CirclePitchAlignment("map")

    /** Circles are aligned to the plane of the viewport, i.e. facing the camera. */
    public val Viewport: CirclePitchAlignment = CirclePitchAlignment("viewport")

    public override val entries: List<CirclePitchAlignment> = listOf(Map, Viewport)
  }
}
