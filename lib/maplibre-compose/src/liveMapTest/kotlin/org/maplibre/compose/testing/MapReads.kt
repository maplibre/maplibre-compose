package org.maplibre.compose.testing

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Ordered engine reads, including resources installed directly by a test. */
internal expect suspend fun MapFixture.readLayerIds(): List<String>

internal expect suspend fun MapFixture.readSourceIds(): List<String>

internal expect suspend fun MapFixture.readLayerProperty(
  layerId: String,
  name: String,
): JsonElement?

/** Includes the library's reserved values, which the public global-state view omits. */
internal expect suspend fun MapFixture.readGlobalState(): JsonObject?
