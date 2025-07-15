package org.maplibre.compose.expressions.value

import org.maplibre.compose.expressions.ast.StringLiteral

/** Text justification options. */
public enum class TextJustify(override val literal: StringLiteral) : EnumValue<TextJustify> {
  /** The text is aligned towards the anchor position. */
  Auto(StringLiteral.of("auto")),

  /** The text is aligned to the left. */
  Left(StringLiteral.of("left")),

  /** The text is centered. */
  Center(StringLiteral.of("center")),

  /** The text is aligned to the right. */
  Right(StringLiteral.of("right")),
}
