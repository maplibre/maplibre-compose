package org.maplibre.compose.layers

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.textOffset
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
  fun dp_text_offsets_stay_fixed_when_font_scale_and_text_size_change() = runTest {
    val source =
      GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
    val binding = RecordingStyleBinding()
    val fontScale = mutableStateOf(1f)
    val textSize = mutableStateOf(16.sp)
    // Reuse the same offsets as font scale and text size change.
    val offsets =
      mapOf(
        "dp" to textOffset((-4).dp, 12.dp),
        "sp" to textOffset((-4).sp, 12.sp),
        "em" to textOffset((-4).em, 12.em),
      )

    fun checkOffsets() {
      for (unit in offsets.keys) {
        val layout = binding.getLayer(unit)!!.toJson()["layout"] as JsonObject
        val renderedSize = layout.getValue("text-size").numberValue()
        assertEquals(textSize.value.value * fontScale.value.toDouble(), renderedSize, 0.0001)
        val offset = layout.getValue("text-offset").jsonArray
        val scale = offset[2].numberValue()
        val components = offset[6].jsonArray[1].jsonArray
        val expectedScale =
          when (unit) {
            "dp" -> 1.0
            "sp" -> fontScale.value.toDouble()
            else -> renderedSize
          }
        for ((index, distance) in listOf(-4.0, 12.0).withIndex()) {
          assertEquals(
            distance * expectedScale,
            components[index].numberValue() * scale * renderedSize,
            0.0001,
            unit,
          )
        }
        // Ordinary DP offsets retain their original units.
        assertEquals(
          Json.parseToJsonElement("""["literal",[-4,12]]""").normalizeNumbers(),
          layout.getValue("icon-offset").normalizeNumbers(),
        )
      }
    }

    composeStyle(
      binding,
      thenChange = {
        checkOffsets()
        fontScale.value = 2f
        textSize.value = 24.sp
      },
    ) {
      CompositionLocalProvider(LocalDensity provides Density(3f, fontScale.value)) {
        for ((unit, value) in offsets) key(unit) {
          SymbolLayer(
            id = unit,
            source = source,
            textSize = const(textSize.value),
            textOffset = value,
            iconOffset = offset((-4).dp, 12.dp),
          )
        }
      }
    }
    checkOffsets()
  }

  private fun JsonElement.numberValue(): Double {
    if (this is JsonPrimitive) return double
    val args = jsonArray
    return when (args[0].jsonPrimitive.contentOrNull) {
      "*" -> args[1].numberValue() * args[2].numberValue()
      "/" -> args[1].numberValue() / args[2].numberValue()
      else -> error("Unexpected numeric expression: $this")
    }
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
            assertEquals(ids.value, binding.layerIds())
            assertEquals(1, binding.imageIds.size)
            initialImageIds = binding.imageIds.toSet()
            ids.value = remaining
          },
        ) {
          for (id in ids.value) {
            key(id) { SymbolLayer(id = id, source = source, iconImage = icon) }
          }
        }

        assertEquals(remaining, binding.layerIds())
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
