package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/** Scaling behavior of circles when the map is pitched. */
@JvmInline
public value class CirclePitchScale private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<CirclePitchScale> {
    /**
     * Circles are scaled according to their apparent distance to the camera, i.e. as if they are on
     * the map.
     */
    public val Map: CirclePitchScale = CirclePitchScale("map")

    /** Circles are not scaled, i.e. as if glued to the viewport. */
    public val Viewport: CirclePitchScale = CirclePitchScale("viewport")

    public override val entries: List<CirclePitchScale> = listOf(Map, Viewport)
  }
}
