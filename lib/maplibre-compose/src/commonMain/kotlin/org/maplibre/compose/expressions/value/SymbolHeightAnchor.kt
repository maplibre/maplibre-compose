package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/**
 * What `symbol-height-offset` is measured from.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class SymbolHeightAnchor private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<SymbolHeightAnchor> {
    /**
     * The offset is measured from the terrain surface below the symbol, or from zero when terrain
     * is off.
     */
    public val Ground: SymbolHeightAnchor = SymbolHeightAnchor("ground")

    /** The offset is measured from sea level. Terrain under the symbol is ignored. */
    public val Absolute: SymbolHeightAnchor = SymbolHeightAnchor("absolute")

    public override val entries: List<SymbolHeightAnchor> = listOf(Ground, Absolute)
  }
}
