package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.StringLiteral

/** Controls whether to show an icon/text when it overlaps other symbols on the map. */
public enum class SymbolOverlap(override val literal: StringLiteral) : EnumValue<SymbolOverlap> {
  /** The icon/text will be hidden if it collides with any other previously drawn symbol. */
  Never(StringLiteral.of("never")),

  /** The icon/text will be visible even if it collides with any other previously drawn symbol. */
  Always(StringLiteral.of("always")),

  /**
   * If the icon/text collides with another previously drawn symbol, the overlap mode for that
   * symbol is checked. If the previous symbol was placed using never overlap mode, the new
   * icon/text is hidden. If the previous symbol was placed using always or cooperative overlap
   * mode, the new icon/text is visible.
   */
  Cooperative(StringLiteral.of("cooperative")),
}
