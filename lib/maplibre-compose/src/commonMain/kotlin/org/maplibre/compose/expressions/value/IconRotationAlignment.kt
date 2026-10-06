package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/**
 * In combination with [SymbolPlacement], determines the rotation behavior of icons.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class IconRotationAlignment private constructor(override val value: String) :
  EnumValue {
  public companion object : EnumType<IconRotationAlignment> {
    /**
     * For [SymbolPlacement.Point], aligns icons east-west. Otherwise, aligns icon x-axes with the
     * line.
     */
    public val Map: IconRotationAlignment = IconRotationAlignment("map")

    /**
     * Produces icons whose x-axes are aligned with the x-axis of the viewport, regardless of the
     * [SymbolPlacement].
     */
    public val Viewport: IconRotationAlignment = IconRotationAlignment("viewport")

    /**
     * For [SymbolPlacement.Point], this is equivalent to [IconRotationAlignment.Viewport].
     * Otherwise, this is equivalent to [IconRotationAlignment.Map].
     */
    public val Auto: IconRotationAlignment = IconRotationAlignment("auto")

    public override val entries: List<IconRotationAlignment> = listOf(Map, Viewport, Auto)
  }
}
