package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/** Orientation of icon when map is pitched. */
@JvmInline
public value class IconPitchAlignment private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<IconPitchAlignment> {
    /** The icon is aligned to the plane of the map. */
    public val Map: IconPitchAlignment = IconPitchAlignment("map")

    /** The icon is aligned to the plane of the viewport, i.e. as if glued to the screen */
    public val Viewport: IconPitchAlignment = IconPitchAlignment("viewport")

    /** Automatically matches the value of [IconRotationAlignment] */
    public val Auto: IconPitchAlignment = IconPitchAlignment("auto")

    public override val entries: List<IconPitchAlignment> = listOf(Map, Viewport, Auto)
  }
}
