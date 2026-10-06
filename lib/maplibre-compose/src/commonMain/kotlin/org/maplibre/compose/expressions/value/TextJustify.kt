package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/**
 * Text justification options.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class TextJustify private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<TextJustify> {
    /** The text is aligned towards the anchor position. */
    public val Auto: TextJustify = TextJustify("auto")

    /** The text is aligned to the left. */
    public val Left: TextJustify = TextJustify("left")

    /** The text is centered. */
    public val Center: TextJustify = TextJustify("center")

    /** The text is aligned to the right. */
    public val Right: TextJustify = TextJustify("right")

    public override val entries: List<TextJustify> = listOf(Auto, Left, Center, Right)
  }
}
