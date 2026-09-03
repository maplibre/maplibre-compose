package org.maplibre.compose.layers

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.compose.expressions.dsl.textVariableAnchorOffset
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.testing.composeStyle
import org.maplibre.compose.testing.supportsComposeRuntimeTests
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class SymbolLayerCompositionTest {

  @Test
  fun variable_anchor_offsets_reach_the_layer_through_the_public_api() = runTest {
    if (!supportsComposeRuntimeTests) return@runTest
    val source =
      GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())

    val style = composeStyle {
      SymbolLayer(
        id = "labels",
        source = source,
        textVariableAnchorOffset =
          textVariableAnchorOffset(
            SymbolAnchor.Top to Offset(0f, 1f),
            SymbolAnchor.Bottom to Offset(0f, -2f),
          ),
      )
    }

    val layer = assertNotNull(style.getLayer("labels"))
    val layout = assertNotNull(layer.toJson()["layout"] as? JsonObject)
    assertEquals(
      Json.parseToJsonElement("""["literal",["top",[0,1],"bottom",[0,-2]]]""").normalizeNumbers(),
      assertNotNull(layout["text-variable-anchor-offset"]).normalizeNumbers(),
    )
  }

  private fun JsonElement.normalizeNumbers(): JsonElement =
    when (this) {
      is JsonArray -> JsonArray(map { it.normalizeNumbers() })
      is JsonObject -> JsonObject(mapValues { (_, value) -> value.normalizeNumbers() })
      is JsonPrimitive -> doubleOrNull?.let(::JsonPrimitive) ?: this
    }
}
