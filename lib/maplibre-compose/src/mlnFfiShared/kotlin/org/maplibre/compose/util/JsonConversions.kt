package org.maplibre.compose.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Conversions between kotlinx-serialization JSON and the FFI's buffer transit: every JSON value
 * crosses the binding as UTF-8 text.
 */

/** Converts kotlinx JSON to the FFI's wire form. */
internal fun JsonElement.toFfiJsonBytes(): ByteArray = toString().encodeToByteArray()

/** Parses a value the FFI returned as UTF-8 JSON. */
internal fun ByteArray.toJsonElement(): JsonElement = Json.parseToJsonElement(decodeToString())
