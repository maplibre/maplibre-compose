package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.StringLiteral

/** Part of the icon/text placed closest to the anchor. */
public enum class SymbolAnchor(override val literal: StringLiteral) : EnumValue<SymbolAnchor> {
  /** The center of the icon is placed closest to the anchor. */
  Center(StringLiteral.of("center")),

  /** The left side of the icon is placed closest to the anchor. */
  Left(StringLiteral.of("left")),

  /** The right side of the icon is placed closest to the anchor. */
  Right(StringLiteral.of("right")),

  /** The top of the icon is placed closest to the anchor. */
  Top(StringLiteral.of("top")),

  /** The bottom of the icon is placed closest to the anchor. */
  Bottom(StringLiteral.of("bottom")),

  /** The top left corner of the icon is placed closest to the anchor. */
  TopLeft(StringLiteral.of("top-left")),

  /** The top right corner of the icon is placed closest to the anchor. */
  TopRight(StringLiteral.of("top-right")),

  /** The bottom left corner of the icon is placed closest to the anchor. */
  BottomLeft(StringLiteral.of("bottom-left")),

  /** The bottom right corner of the icon is placed closest to the anchor. */
  BottomRight(StringLiteral.of("bottom-right")),
}
