package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/**
 * In combination with [SymbolPlacement], determines the rotation behavior of the individual glyphs
 * forming the text.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class TextRotationAlignment private constructor(override val value: String) :
  EnumValue {
  public companion object : EnumType<TextRotationAlignment> {
    /**
     * For [SymbolPlacement.Point], aligns text east-west. Otherwise, aligns text x-axes with the
     * line.
     */
    public val Map: TextRotationAlignment = TextRotationAlignment("map")

    /**
     * Produces glyphs whose x-axes are aligned with the x-axis of the viewport, regardless of the
     * [SymbolPlacement].
     */
    public val Viewport: TextRotationAlignment = TextRotationAlignment("viewport")

    /**
     * For [SymbolPlacement.Point], this is equivalent to [TextRotationAlignment.Viewport].
     * Otherwise, aligns glyphs to the x-axis of the viewport and places them along the line.
     *
     * Not yet supported on native
     * ([maplibre-native#250](https://github.com/maplibre/maplibre-native/issues/250)).
     */
    public val ViewportGlyph: TextRotationAlignment = TextRotationAlignment("viewport-glyph")

    /**
     * For [SymbolPlacement.Point], this is equivalent to [TextRotationAlignment.Viewport].
     * Otherwise, this is equivalent to [TextRotationAlignment.Map].
     */
    public val Auto: TextRotationAlignment = TextRotationAlignment("auto")

    public override val entries: List<TextRotationAlignment> =
      listOf(Map, Viewport, ViewportGlyph, Auto)
  }
}
