package org.maplibre.compose.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Parses a value the FFI returned as JSON text. */
internal fun String.toJsonElement(): JsonElement = Json.parseToJsonElement(this)
