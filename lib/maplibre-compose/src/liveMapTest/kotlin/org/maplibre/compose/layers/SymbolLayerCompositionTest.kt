package org.maplibre.compose.layers

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.coalesce
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.sp
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.dsl.textVariableAnchorOffset
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.FONT_SCALE_GLOBAL_STATE
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.testing.composeStyle
import org.maplibre.compose.testing.runGraphicsTest
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
            SymbolAnchor.Top to textOffset(0.em, 1.em),
            SymbolAnchor.Bottom to textOffset(0.em, (-2).em),
          ),
      )
    }

    val layer = assertNotNull(style.getLayer("labels"))
    val layout = assertNotNull(layer.toJson()["layout"] as? JsonObject)
    assertEquals(
      Json.parseToJsonElement(
          """["let","semiliteral_value",["semiliteral",["top",["literal",[0,1]],"bottom",["literal",[0,-2]]]],["var","semiliteral_value"]]"""
        )
        .normalizeNumbers(),
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
        val renderedSize = layout.getValue("text-size").numberValue(binding.globalStateValues)
        assertEquals(textSize.value.value * fontScale.value.toDouble(), renderedSize, 0.0001)
        val offset = layout.getValue("text-offset").jsonArray
        val components =
          if (offset[0].jsonPrimitive.content == "literal") offset[1].jsonArray
          else offset[2].jsonArray[1].jsonArray
        val variableOffsets =
          layout.getValue("text-variable-anchor-offset").jsonArray[2].jsonArray[1].jsonArray
        assertEquals("top", variableOffsets[0].jsonPrimitive.contentOrNull)
        assertEquals(offset, variableOffsets[1])
        val expectedScale =
          when (unit) {
            "dp" -> 1.0
            "sp" -> fontScale.value.toDouble()
            else -> renderedSize
          }
        for ((index, distance) in listOf(-4.0, 12.0).withIndex()) {
          assertEquals(
            distance * expectedScale,
            components[index].numberValue(binding.globalStateValues) * renderedSize,
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
            textVariableAnchorOffset = textVariableAnchorOffset(SymbolAnchor.Top to value),
            iconOffset = offset((-4).dp, 12.dp),
          )
        }
      }
    }
    checkOffsets()
  }

  @Test
  fun root_font_scale_changes_one_global_without_rewriting_layer_expressions() = runTest {
    val source =
      GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
    val binding = RecordingStyleBinding()
    val fontScale = mutableStateOf(1f)
    var originalLayers = emptyMap<String, JsonObject>()
    var originalWrites = emptyList<Pair<String, String>>()
    composeStyle(
      binding,
      density = { Density(3f, fontScale.value) },
      thenChange = {
        originalLayers = binding.layers.toMap()
        originalWrites = binding.layerPropertyWrites.toList()
        fontScale.value = 2f
      },
    ) {
      SymbolLayer("normal", source, textSize = const(16.sp), textOffset = textOffset(0.dp, 12.dp))
      CompositionLocalProvider(LocalDensity provides Density(3f, 1.25f)) {
        SymbolLayer(
          "override",
          source,
          textSize = const(16.sp),
          textOffset = textOffset(0.dp, 12.dp),
        )
      }
    }
    assertEquals(originalLayers, binding.layers)
    assertEquals(originalWrites, binding.layerPropertyWrites)
    assertEquals(listOf(1.0, 2.0), binding.globalStateWrites.map { it.second.jsonPrimitive.double })
    assertTrue(binding.globalStateWrites.all { it.first == FONT_SCALE_GLOBAL_STATE })
    val normal = binding.layers.getValue("normal").getValue("layout") as JsonObject
    val override = binding.layers.getValue("override").getValue("layout") as JsonObject
    assertEquals(32.0, normal.getValue("text-size").numberValue(binding.globalStateValues))
    assertEquals(20.0, override.getValue("text-size").numberValue(binding.globalStateValues))
  }

  @Test
  fun global_text_size_and_font_scale_keep_dp_offsets_fixed() = runTest {
    val source =
      GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
    val binding = RecordingStyleBinding()
    val size = org.maplibre.compose.expressions.dsl.globalState("label-size").asNumber().sp
    composeStyle(binding, density = { Density(2f, 1.5f) }) {
      SymbolLayer("labels", source, textSize = size, textOffset = textOffset(0.dp, 12.dp))
    }
    val layout = binding.layers.getValue("labels").getValue("layout") as JsonObject
    val offsetY = layout.getValue("text-offset").jsonArray[2].jsonArray[1].jsonArray[1]
    for (textSize in listOf(16f, 24f)) {
      val globals = binding.globalStateValues + ("label-size" to JsonPrimitive(textSize))
      val renderedSize = layout.getValue("text-size").numberValue(globals)
      assertEquals(textSize * 1.5, renderedSize, 0.0001)
      assertEquals(12.0, offsetY.numberValue(globals) * renderedSize, 0.0001)
    }
  }

  @Test
  fun zoom_text_size_keeps_interpolation_at_the_root_and_em_offsets_literal() = runTest {
    val source =
      GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
    val binding = RecordingStyleBinding()
    composeStyle(binding) {
      SymbolLayer(
        "labels",
        source,
        textSize = interpolate(linear(), zoom(), 0 to const(16.sp), 10 to const(32.sp)),
        textOffset = textOffset(1.em, 2.em),
      )
    }
    val layout = binding.layers.getValue("labels").getValue("layout") as JsonObject
    val size = layout.getValue("text-size").jsonArray
    assertEquals("interpolate", size[0].jsonPrimitive.content)
    assertEquals(16.0, size[4].numberValue(binding.globalStateValues))
    assertEquals(32.0, size[6].numberValue(binding.globalStateValues))
    assertEquals(
      Json.parseToJsonElement("""["literal",[1,2]]""").normalizeNumbers(),
      layout.getValue("text-offset").normalizeNumbers(),
    )
  }

  private fun JsonElement.numberValue(globals: Map<String, JsonElement> = emptyMap()): Double {
    if (this is JsonPrimitive) return double
    val args = jsonArray
    return when (args[0].jsonPrimitive.contentOrNull) {
      "*" -> args[1].numberValue(globals) * args[2].numberValue(globals)
      "/" -> args[1].numberValue(globals) / args[2].numberValue(globals)
      "number" -> args[1].numberValue(globals)
      "global-state" -> globals.getValue(args[1].jsonPrimitive.content).numberValue(globals)
      else -> error("Unexpected numeric expression: $this")
    }
  }

  @Test
  fun keyed_layers_reorder_and_release_their_images() = runGraphicsTest { graphics ->
    val source =
      GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
    val pixels = ImageBitmap(2, 2)
    val icon =
      coalesce(
        image(pixels),
        image(pixels, isSdf = true),
        image(ColorPainter(Color.Red), size = DpSize(2.dp, 2.dp)),
      )
    val imageCount = 3
    for (remaining in listOf(listOf("c", "a"), emptyList())) {
      val ids = mutableStateOf(listOf("a", "b", "c"))
      val binding = RecordingStyleBinding()
      var initialImageIds = emptySet<String>()
      composeStyle(
        style = binding,
        graphicsContext = graphics,
        thenChange = {
          assertEquals(ids.value, binding.layerIds())
          assertEquals(imageCount, binding.imageIds.size)
          initialImageIds = binding.imageIds.toSet()
          ids.value = remaining
        },
      ) {
        for (id in ids.value) {
          key(id) { SymbolLayer(id = id, source = source, iconImage = icon) }
        }
      }

      assertEquals(remaining, binding.layerIds())
      assertEquals(if (remaining.isEmpty()) 0 else imageCount, binding.imageIds.size)
      assertTrue(
        initialImageIds.containsAll(binding.imageIds),
        "surviving nodes retain their images",
      )
    }
  }

  @Test
  fun one_painter_is_prepared_once_across_properties_and_layers() = runGraphicsTest { graphics ->
    val source =
      GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
    var draws = 0
    val painter =
      object : Painter() {
        override val intrinsicSize = Size(4f, 4f)

        override fun DrawScope.onDraw() {
          draws++
          drawRect(Color.Red)
        }
      }
    val count = mutableStateOf(3)
    var originalId: String? = null
    val binding =
      composeStyle(
        graphicsContext = graphics,
        thenChange = { count.value = 1 },
        onRevision = { revision ->
          if (!revision.imagesPending && revision.layers.isNotEmpty()) {
            val id = revision.images.single().id
            if (originalId == null) originalId = id else assertEquals(originalId, id)
            revision.layers.forEach { layer ->
              val json = layer.definition.value
              val property =
                (json["layout"] as? JsonObject)?.get("icon-image")
                  ?: (json["paint"] as JsonObject).getValue("fill-pattern")
              assertEquals(id, property.jsonArray[1].jsonPrimitive.content)
            }
          }
        },
      ) {
        if (count.value > 1) SymbolLayer("first", source, iconImage = image(painter))
        if (count.value > 2) SymbolLayer("second", source, iconImage = image(painter))
        if (count.value > 0) FillLayer("pattern", source, pattern = image(painter))
      }
    assertEquals(1, draws)
    assertEquals(
      listOf(originalId),
      binding.imageIds.toList(),
    )
  }

  @Test
  fun equal_pixels_share_an_image_but_different_rendering_options_do_not() =
    runGraphicsTest { graphics ->
      val source =
        GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
      fun painter() =
        object : Painter() {
          override val intrinsicSize = Size(4f, 4f)

          override fun DrawScope.onDraw() {
            drawRect(Color.Red)
          }
        }
      val first = painter()
      val second = painter()
      val binding =
        composeStyle(graphicsContext = graphics) {
          SymbolLayer("first", source, iconImage = image(first))
          SymbolLayer("equal-pixels", source, iconImage = image(second))
          SymbolLayer("different-size", source, iconImage = image(first, size = DpSize(8.dp, 8.dp)))
        }
      assertEquals(2, binding.imageIds.size)
      fun iconId(id: String) =
        (binding.layers.getValue(id)["layout"] as JsonObject).getValue("icon-image")
      assertEquals(iconId("first"), iconId("equal-pixels"))
      assertTrue(iconId("first") != iconId("different-size"))
    }

  @Test
  fun changing_an_expression_keeps_its_unchanged_painter_visible() = runGraphicsTest { graphics ->
    val source =
      GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
    val frame = mutableStateOf(0)
    var draws = 0
    val painter =
      object : Painter() {
        override val intrinsicSize = Size(4f, 4f)

        override fun DrawScope.onDraw() {
          draws++
          drawRect(Color.Red)
        }
      }
    var ready = false
    var imageId: String? = null
    composeStyle(
      graphicsContext = graphics,
      onRevision = { revision ->
        if (!ready && revision.images.isNotEmpty()) imageId = revision.images.single().id
        if (ready) {
          assertEquals(listOf(imageId), revision.images.map { it.id })
          val layout = revision.layers.single().definition.value["layout"] as JsonObject
          assertTrue(layout["icon-image"] != null && layout["icon-image"] != JsonNull)
        }
      },
      thenChange = {
        assertNotNull(imageId)
        ready = true
        frame.value++
      },
    ) {
      SymbolLayer(
        id = "animated",
        source = source,
        iconImage = coalesce(image(painter), image("fallback-${frame.value}")),
        iconSize = const(1f + frame.value * 0.1f),
      )
    }
    assertEquals(1, draws)
  }

  @Test
  fun replacing_a_painter_releases_its_image_and_never_exposes_stale_ids() =
    runGraphicsTest { graphics ->
      val source =
        GeoJsonSource("features", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
      val replace = mutableStateOf(false)
      val red = ColorPainter(Color.Red)
      val blue = ColorPainter(Color.Blue)
      var initialId: String? = null
      var replacementId: String? = null

      fun DesiredStyleRevision.iconId(): String? {
        val layout = layers.singleOrNull()?.definition?.value?.get("layout") as? JsonObject
        return (layout?.get("icon-image") as? JsonArray)?.get(1)?.jsonPrimitive?.contentOrNull
      }

      composeStyle(
        graphicsContext = graphics,
        onRevision = { revision ->
          val id = revision.iconId()
          if (replace.value && revision.imagesPending) assertEquals(initialId, id)
          if (id != null) {
            val image = assertNotNull(revision.images.singleOrNull { it.id == id })
            val pixels = IntArray(16)
            image.image.toImageBitmap().readPixels(pixels)
            val expected =
              if (replace.value && !revision.imagesPending) 0xff0000ff.toInt()
              else 0xffff0000.toInt()
            assertEquals(List(16) { expected }, pixels.toList())
            if (replace.value && !revision.imagesPending) replacementId = id else initialId = id
          }
        },
        thenChange = {
          assertNotNull(initialId)
          replace.value = true
        },
      ) {
        SymbolLayer(
          id = "replaced",
          source = source,
          iconImage = image(if (replace.value) blue else red, size = DpSize(4.dp, 4.dp)),
        )
      }
      assertNotNull(replacementId)
    }

  private fun JsonElement.normalizeNumbers(): JsonElement =
    when (this) {
      is JsonArray -> JsonArray(map { it.normalizeNumbers() })
      is JsonObject -> JsonObject(mapValues { (_, value) -> value.normalizeNumbers() })
      is JsonPrimitive -> doubleOrNull?.let(::JsonPrimitive) ?: this
    }
}
