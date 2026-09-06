package org.maplibre.compose.layers

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.textVariableAnchorOffset
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.testing.composeStyle
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class SymbolLayerCompositionTest {

  @Test
  fun variable_anchor_offsets_reach_the_layer_through_the_public_api() = runTest {
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

  @Test
  fun keyed_layers_reorder_and_release_shared_images() = runTest {
    val source =
      GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
    val icons =
      listOf(
        image(ImageBitmap(2, 2)),
        image(ColorPainter(Color.Red), size = DpSize(2.dp, 2.dp)),
      )
    for (icon in icons) {
      for (remaining in listOf(listOf("c", "a"), emptyList())) {
        val ids = mutableStateOf(listOf("a", "b", "c"))
        val binding = RecordingStyleBinding()
        var initialImageIds = emptySet<String>()
        composeStyle(
          style = binding,
          thenChange = {
            assertEquals(ids.value, binding.getLayers().map { it.id })
            assertEquals(1, binding.imageIds.size)
            initialImageIds = binding.imageIds.toSet()
            ids.value = remaining
          },
        ) {
          for (id in ids.value) {
            key(id) { SymbolLayer(id = id, source = source, iconImage = icon) }
          }
        }

        assertEquals(remaining, binding.getLayers().map { it.id })
        assertEquals(if (remaining.isEmpty()) emptySet() else initialImageIds, binding.imageIds)
      }
    }
  }

  private fun JsonElement.normalizeNumbers(): JsonElement =
    when (this) {
      is JsonArray -> JsonArray(map { it.normalizeNumbers() })
      is JsonObject -> JsonObject(mapValues { (_, value) -> value.normalizeNumbers() })
      is JsonPrimitive -> doubleOrNull?.let(::JsonPrimitive) ?: this
    }
}
