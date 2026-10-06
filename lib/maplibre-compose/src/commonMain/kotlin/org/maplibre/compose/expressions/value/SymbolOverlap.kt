package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/**
 * Controls whether to show an icon/text when it overlaps other symbols on the map.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class SymbolOverlap private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<SymbolOverlap> {
    /** The icon/text will be hidden if it collides with any other previously drawn symbol. */
    public val Never: SymbolOverlap = SymbolOverlap("never")

    /** The icon/text will be visible even if it collides with any other previously drawn symbol. */
    public val Always: SymbolOverlap = SymbolOverlap("always")

    /**
     * If the icon/text collides with another previously drawn symbol, the overlap mode for that
     * symbol is checked. If the previous symbol was placed using never overlap mode, the new
     * icon/text is hidden. If the previous symbol was placed using always or cooperative overlap
     * mode, the new icon/text is visible.
     */
    public val Cooperative: SymbolOverlap = SymbolOverlap("cooperative")

    public override val entries: List<SymbolOverlap> = listOf(Never, Always, Cooperative)
  }
}
