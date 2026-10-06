package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/**
 * Scales the icon to fit around the associated text.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class IconTextFit private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<IconTextFit> {
    /** The icon is displayed at its intrinsic aspect ratio. */
    public val None: IconTextFit = IconTextFit("none")

    /** The icon is scaled in the x-dimension to fit the width of the text. */
    public val Width: IconTextFit = IconTextFit("width")

    /** The icon is scaled in the y-dimension to fit the height of the text. */
    public val Height: IconTextFit = IconTextFit("height")

    /** The icon is scaled in both x- and y-dimensions. */
    public val Both: IconTextFit = IconTextFit("both")

    public override val entries: List<IconTextFit> = listOf(None, Width, Height, Both)
  }
}
