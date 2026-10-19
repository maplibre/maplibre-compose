@file:OptIn(DelicateMaplibreComposeApi::class, ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.maplibre.compose.map.withPlatformMap
import org.maplibre.compose.util.DelicateMaplibreComposeApi
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

internal actual suspend fun MapFixture.readLayerIds(): List<String> =
  state.withPlatformMap { map.listStyleLayers() }.await().map { it.id }

internal actual suspend fun MapFixture.readSourceIds(): List<String> =
  state.withPlatformMap { map.listStyleSources() }.await().map { it.id }

internal actual suspend fun MapFixture.readLayerProperty(
  layerId: String,
  name: String,
): JsonElement? =
  when (name) {
    "minzoom",
    "maxzoom" -> {
      val layer = state.withPlatformMap { map.getStyleLayer(layerId) }.await()
      (if (name == "minzoom") layer?.minZoom else layer?.maxZoom)
        ?.takeIf(Double::isFinite)
        ?.let(::JsonPrimitive)
    }
    else ->
      state
        .withPlatformMap {
          map.getStyleLayerProperty(layerId, name)
        }
        .await()
        ?.let { Json.parseToJsonElement(it.decodeToString()) }
  }

internal actual suspend fun MapFixture.readGlobalState(): JsonObject? =
  Json.parseToJsonElement(state.withPlatformMap { map.getGlobalState() }.await().decodeToString())
    .jsonObject
