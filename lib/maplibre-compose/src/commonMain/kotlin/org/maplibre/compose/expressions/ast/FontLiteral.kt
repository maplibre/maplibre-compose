package org.maplibre.compose.expressions.ast

import org.maplibre.compose.expressions.value.ListValue
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.style.FontFile

/**
 * A [Literal] representing a font stack whose first name is backed by a TTF or OTF file. The file
 * is registered with the style when a layer references the literal, and released when no layer
 * references it any more. The literal keeps its own copy of the bytes.
 */
public class FontLiteral
private constructor(
  /** The name the file is registered under. */
  public val name: String,
  internal val file: FontFile,
  /** Stack names tried after [name]. */
  public val fallbacks: List<String>,
) : Literal<ListValue<StringValue>, ByteArray> {
  /** A copy of the file; the literal's own bytes are never exposed. */
  override val value: ByteArray
    get() = file.bytes.copyOf()

  /** The stack this literal compiles to. */
  public val stack: List<String>
    get() = listOf(name) + fallbacks

  override fun compile(context: ExpressionContext): CompiledListLiteral<StringValue> =
    CompiledListLiteral.of(context.resolveFont(this).map(StringLiteral::of))

  override fun visit(block: (Expression<*>) -> Unit): Unit = block(this)

  override fun equals(other: Any?): Boolean =
    other is FontLiteral && name == other.name && file == other.file && fallbacks == other.fallbacks

  override fun hashCode(): Int =
    31 * (31 * name.hashCode() + file.hashCode()) + fallbacks.hashCode()

  override fun toString(): String = "FontLiteral(name=$name, file=$file, fallbacks=$fallbacks)"

  public companion object {
    public fun of(name: String, bytes: ByteArray, fallbacks: List<String>): FontLiteral {
      require(name.isNotBlank()) { "A font name must not be blank" }
      require(bytes.isNotEmpty()) { "A font file must not be empty" }
      return FontLiteral(name, FontFile(bytes.copyOf()), fallbacks.toList())
    }
  }
}
