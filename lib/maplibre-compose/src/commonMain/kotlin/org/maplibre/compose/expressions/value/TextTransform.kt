package org.maplibre.compose.expressions.value

import kotlin.jvm.JvmInline

/**
 * Specifies how to capitalize text, similar to the CSS text-transform property.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class TextTransform private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<TextTransform> {
    /** The text is not altered. */
    public val None: TextTransform = TextTransform("none")

    /** Forces all letters to be displayed in uppercase. */
    public val Uppercase: TextTransform = TextTransform("uppercase")

    /** Forces all letters to be displayed in lowercase. */
    public val Lowercase: TextTransform = TextTransform("lowercase")

    public override val entries: List<TextTransform> = listOf(None, Uppercase, Lowercase)
  }
}
