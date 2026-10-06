package org.maplibre.compose.expressions.value

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline

/**
 * Determines whether overlapping symbols in the same layer are rendered in the order that they
 * appear in the data source or by their y-position relative to the viewport. To control the order
 * and prioritization of symbols otherwise, use `sortKey`.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
@JvmInline
public value class SymbolZOrder private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<SymbolZOrder> {
    /**
     * Sorts symbols by `sortKey` if set. Otherwise, sorts symbols by their y-position relative to
     * the viewport if `iconAllowOverlap` or `textAllowOverlap` is set to `true` or
     * `iconIgnorePlacement` or `textIgnorePlacement` is `false`.
     */
    public val Auto: SymbolZOrder = SymbolZOrder("auto")

    /**
     * Sorts symbols by their y-position relative to the viewport if `iconAllowOverlap` or
     * `textAllowOverlap` is set to `true` or `iconIgnorePlacement` or `textIgnorePlacement` is
     * `false`.
     */
    public val ViewportY: SymbolZOrder = SymbolZOrder("viewport-y")

    /**
     * Sorts symbols by `sortKey` if set. Otherwise, no sorting is applied; symbols are rendered in
     * the same order as the source data.
     */
    public val Source: SymbolZOrder = SymbolZOrder("source")

    public override val entries: List<SymbolZOrder> = listOf(Auto, ViewportY, Source)
  }
}
