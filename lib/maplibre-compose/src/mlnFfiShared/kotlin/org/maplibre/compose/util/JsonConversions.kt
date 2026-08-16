package org.maplibre.compose.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Conversions between kotlinx-serialization JSON and the FFI's UTF-8 text. */

/** Converts kotlinx JSON to UTF-8 text. */
internal fun JsonElement.toJsonBytes(): ByteArray = toString().encodeToByteArray()

/** Parses a value the FFI returned as UTF-8 JSON. */
internal fun ByteArray.toJsonElement(): JsonElement = Json.parseToJsonElement(decodeToString())
