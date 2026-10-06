package org.maplibre.compose.expressions.value

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline

/**
 * How the text will be laid out.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
@JvmInline
public value class TextWritingMode private constructor(override val value: String) : EnumValue {
  public companion object : EnumType<TextWritingMode> {
    /**
     * If a text's language supports horizontal writing mode, symbols with point placement would be
     * laid out horizontally.
     */
    public val Horizontal: TextWritingMode = TextWritingMode("horizontal")

    /**
     * If a text's language supports vertical writing mode, symbols with point placement would be
     * laid out vertically.
     */
    public val Vertical: TextWritingMode = TextWritingMode("vertical")

    public override val entries: List<TextWritingMode> = listOf(Horizontal, Vertical)
  }
}
