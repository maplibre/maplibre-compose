package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.StringLiteral

/** What `symbol-height-offset` is measured from. */
public enum class SymbolHeightAnchor(override val literal: StringLiteral) :
  EnumValue<SymbolHeightAnchor> {
  /**
   * The offset is measured from the terrain surface below the symbol, or from zero when terrain is
   * off.
   */
  Ground(StringLiteral.of("ground")),

  /** The offset is measured from sea level. Terrain under the symbol is ignored. */
  Absolute(StringLiteral.of("absolute")),
}
