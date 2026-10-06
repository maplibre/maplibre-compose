package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/**
 * Orientation of text when map is pitched.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class TextPitchAlignment private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<TextPitchAlignment> {
    /** The text is aligned to the plane of the map. */
    public val Map: TextPitchAlignment = TextPitchAlignment("map")

    /** The text is aligned to the plane of the viewport, i.e. as if glued to the screen */
    public val Viewport: TextPitchAlignment = TextPitchAlignment("viewport")

    /** Automatically matches the value of [TextRotationAlignment] */
    public val Auto: TextPitchAlignment = TextPitchAlignment("auto")

    public override val entries: List<TextPitchAlignment> = listOf(Map, Viewport, Auto)
  }
}
