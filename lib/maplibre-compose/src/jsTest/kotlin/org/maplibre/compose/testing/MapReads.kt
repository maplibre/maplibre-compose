package org.maplibre.compose.testing

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal actual suspend fun MapFixture.readLayerIds(): List<String> = checkNotNull(style).layerIds()

internal actual suspend fun MapFixture.readSourceIds(): List<String> =
  checkNotNull(style).sourceIds()

internal actual suspend fun MapFixture.readLayerProperty(
  layerId: String,
  name: String,
): JsonElement? = checkNotNull(style).layerProperty(layerId, name)

internal actual suspend fun MapFixture.readGlobalState(): JsonObject? =
  checkNotNull(style).globalState()
