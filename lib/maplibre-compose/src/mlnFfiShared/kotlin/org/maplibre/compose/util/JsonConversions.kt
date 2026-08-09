package org.maplibre.compose.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Encodes one JSON value as UTF-8 for the FFI buffer API. */
internal fun JsonElement.toJsonBytes(): ByteArray = toString().encodeToByteArray()

/** Decodes one UTF-8 JSON value returned by the FFI buffer API. */
internal fun ByteArray.toJsonElement(): JsonElement = Json.parseToJsonElement(decodeToString())
