package org.maplibre.compose.expressions.internal

import androidx.compose.ui.graphics.toArgb
import org.maplibre.compose.expressions.ast.BooleanLiteral
import org.maplibre.compose.expressions.ast.ColorLiteral
import org.maplibre.compose.expressions.ast.CompiledCallArgument
import org.maplibre.compose.expressions.ast.CompiledExpression
import org.maplibre.compose.expressions.ast.CompiledFunctionCall
import org.maplibre.compose.expressions.ast.CompiledListLiteral
import org.maplibre.compose.expressions.ast.CompiledOptions
import org.maplibre.compose.expressions.ast.CompiledSemiliteral
import org.maplibre.compose.expressions.ast.DpPaddingLiteral
import org.maplibre.compose.expressions.ast.FloatLiteral
import org.maplibre.compose.expressions.ast.NullLiteral
import org.maplibre.compose.expressions.ast.OffsetLiteral
import org.maplibre.compose.expressions.ast.ProjectionTransitionLiteral
import org.maplibre.compose.expressions.ast.StringLiteral
import org.maplibre.compose.expressions.ast.Verbatim
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

/** Writes style-spec forms once, independently of the engine's transport representation. */
@OptIn(ExperimentalMaplibreComposeApi::class)
internal fun CompiledExpression<*>.writeStyleValue(
  writer: StyleValueWriter,
  inLiteral: Boolean = false,
) {
  when (this) {
    NullLiteral -> writer.nullValue()
    is BooleanLiteral -> writer.booleanValue(value)
    is FloatLiteral -> writer.numberValue(value)
    is StringLiteral -> writer.stringValue(value)
    is OffsetLiteral ->
      writer.literalArray(inLiteral) {
        numberValue(value.x)
        numberValue(value.y)
      }
    is ColorLiteral ->
      writer.stringValue(
        value.toArgb().let {
          "rgba(${(it shr 16) and 0xFF}, ${(it shr 8) and 0xFF}, ${it and 0xFF}, ${value.alpha})"
        }
      )
    // Projection transitions must remain bare arrays; a literal wrapper fails validation.
    is ProjectionTransitionLiteral -> {
      writer.beginArray()
      writer.stringValue(value.from.value)
      writer.stringValue(value.to.value)
      writer.numberValue(value.progress)
      writer.endArray()
    }
    is DpPaddingLiteral ->
      writer.literalArray(inLiteral) {
        // Style order is top, right, bottom, left.
        numberValue(value.top.value)
        numberValue(value.right.value)
        numberValue(value.bottom.value)
        numberValue(value.left.value)
      }
    is CompiledFunctionCall -> {
      writer.beginArray()
      writer.stringValue(name)
      args.forEach { it.writeStyleArgument(writer) }
      writer.endArray()
    }
    is CompiledSemiliteral<*> -> {
      // The pinned parser needs unconstrained array context for semiliteral children.
      // https://github.com/maplibre/maplibre-style-spec/blob/v26.4.2/src/expression/definitions/semiliteral.ts
      writer.beginArray()
      writer.stringValue("let")
      writer.stringValue("semiliteral_value")
      writer.beginArray()
      writer.stringValue("semiliteral")
      writer.beginArray()
      elements.forEach { it.writeStyleValue(writer) }
      writer.endArray()
      writer.endArray()
      writer.beginArray()
      writer.stringValue("var")
      writer.stringValue("semiliteral_value")
      writer.endArray()
      writer.endArray()
    }
    is CompiledListLiteral<*> ->
      writer.literalArray(inLiteral) {
        value.forEach { it.writeStyleValue(this, inLiteral = true) }
      }
  }
}

private fun CompiledCallArgument.writeStyleArgument(writer: StyleValueWriter) {
  when (this) {
    is CompiledExpression<*> -> writeStyleValue(writer)
    is Verbatim -> writer.jsonValue(json)
    is CompiledOptions -> {
      writer.beginObject()
      entries.forEach { (name, value) ->
        writer.name(name)
        value.writeStyleValue(writer)
      }
      writer.endObject()
    }
  }
}

private inline fun StyleValueWriter.literalArray(
  inLiteral: Boolean,
  values: StyleValueWriter.() -> Unit,
) {
  if (!inLiteral) {
    beginArray()
    stringValue("literal")
  }
  beginArray()
  values()
  endArray()
  if (!inLiteral) endArray()
}
