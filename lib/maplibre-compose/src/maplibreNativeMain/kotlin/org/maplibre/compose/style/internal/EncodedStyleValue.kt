package org.maplibre.compose.style.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.maplibre.compose.expressions.internal.TextStyleValueWriter

internal actual class EncodedStyleValue(private val text: String) {
  private val bytes: ByteArray by lazy { text.encodeToByteArray() }

  fun toJsonBytes(): ByteArray = bytes

  actual fun toJson(): String = text

  actual fun toJsonElement(): JsonElement = Json.parseToJsonElement(text)

  actual override fun equals(other: Any?): Boolean =
    other is EncodedStyleValue && text == other.text

  actual override fun hashCode(): Int = text.hashCode()
}

internal actual fun encodeStyleValue(value: StyleValue): EncodedStyleValue =
  EncodedStyleValue(TextStyleValueWriter().also { value.write(it) }.result)
