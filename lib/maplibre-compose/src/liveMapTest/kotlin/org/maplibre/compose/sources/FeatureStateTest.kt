package org.maplibre.compose.sources

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.layers.CircleLayerDescriptor
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

class FeatureStateTest {

  @Test
  fun an_unattached_source_answers_empty_and_ignores_writes(): MapTestResult = runMapTest {
    val source =
      GeoJsonSource(
        id = "unattached",
        data = GeoJsonData.Features(buildFeatureCollection<Geometry, JsonObject?> {}),
        options = GeoJsonOptions(),
      )
    assertEquals(JsonObject(emptyMap()), source.getFeatureState("pin"))
    source.setFeatureState("pin", buildJsonObject { put("selected", true) })
    assertEquals(JsonObject(emptyMap()), source.getFeatureState("pin"))
    source.removeFeatureState("pin")
    source.resetFeatureStates()

    val vector = VectorSource(id = "unattached-vector", uri = "https://example.invalid/tiles.json")
    assertEquals(JsonObject(emptyMap()), vector.getFeatureState("layer", "pin"))
    vector.setFeatureState("layer", "pin", buildJsonObject { put("selected", true) })
    assertEquals(JsonObject(emptyMap()), vector.getFeatureState("layer", "pin"))
    vector.removeFeatureState("layer", "pin")
    vector.resetFeatureStates("layer")
  }

  @Test
  fun feature_state_is_merged_removed_and_read_back(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BLACK_STYLE)
      val style = assertNotNull(fixture.style)
      val source = attachedPointSource(style, featureId = "7")
      attachCircleLayer(style, source)
      fixture.pumpUntilPixel("the circle to be drawn", CENTER, CENTER, BLUE)

      source.setFeatureState("7", buildJsonObject { put("selected", true) })
      assertEquals(true, source.getFeatureState("7")["selected"]?.jsonPrimitive?.boolean)

      source.setFeatureState("7", buildJsonObject { put("hover", true) })
      val merged = source.getFeatureState("7")
      assertEquals(true, merged["selected"]?.jsonPrimitive?.boolean)
      assertEquals(true, merged["hover"]?.jsonPrimitive?.boolean)

      source.removeFeatureState("7", "hover")
      fixture.pump(frames = 1)
      val afterRemove = source.getFeatureState("7")
      assertEquals(true, afterRemove["selected"]?.jsonPrimitive?.boolean)
      assertEquals(null, afterRemove["hover"])

      source.resetFeatureStates()
      fixture.pump(frames = 1)
      assertEquals(JsonObject(emptyMap()), source.getFeatureState("7"))
    }
  }

  @Test
  fun a_quoted_numeric_string_id_keeps_its_own_state(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BLACK_STYLE)
      val style = assertNotNull(fixture.style)
      val source = attachedPointSource(style, featureId = "7", quoteId = true)
      attachCircleLayer(style, source)
      fixture.pumpUntilPixel("the circle to be drawn", CENTER, CENTER, BLUE)

      source.setFeatureState("7", buildJsonObject { put("selected", true) })
      assertEquals(true, source.getFeatureState("7")["selected"]?.jsonPrimitive?.boolean)
    }
  }

  @Test
  fun a_paint_expression_reads_feature_state(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BLACK_STYLE)
      fixture.session.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 1.0))
      val style = assertNotNull(fixture.style)
      val source = attachedPointSource(style, featureId = "1")
      attachCircleLayer(style, source)

      fixture.pumpUntilPixel("the unselected circle to be drawn", CENTER, CENTER, BLUE)
      source.setFeatureState("1", buildJsonObject { put("selected", true) })
      fixture.pumpUntilPixel("the selected circle to be drawn", CENTER, CENTER, RED)
      source.removeFeatureState("1")
      fixture.pumpUntilPixel("the circle to return to its fallback color", CENTER, CENTER, BLUE)
    }
  }

  private fun attachedPointSource(
    style: StyleBinding,
    featureId: String,
    quoteId: Boolean = false,
  ): GeoJsonSource {
    val source =
      GeoJsonSource(
        id = "points",
        data =
          GeoJsonData.Features(
            buildFeatureCollection<Geometry, JsonObject?> {
              addFeature(geometry = Point(Position(0.0, 0.0))) {
                // Without promoteId, MapLibre identifies a feature for state by an integer id
                // unless the GeoJSON id is a quoted string.
                val number = featureId.toLongOrNull()
                if (number != null && !quoteId) setId(number) else setId(featureId)
              }
            }
          ),
        options = GeoJsonOptions(),
      )
    style.addSource(source)
    return source
  }

  private fun attachCircleLayer(style: StyleBinding, source: GeoJsonSource) {
    val layer = CircleLayerDescriptor("circles", source)
    layer.setCircleRadius(const(48.dp).compile(ExpressionContext.None))
    layer.setCircleColor(
      switch(
          condition(
            test = feature.state<BooleanValue>("selected").asBoolean(const(false)),
            output = const(Color.Red),
          ),
          fallback = const(Color.Blue),
        )
        .compile(ExpressionContext.None)
    )
    style.addLayer(layer)
  }

  private companion object {
    const val CENTER = 256

    val RED = RgbaPixel(red = 255, green = 0, blue = 0, alpha = 255)
    val BLUE = RgbaPixel(red = 0, green = 0, blue = 255, alpha = 255)

    val BLACK_STYLE =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "sources": {},
          "layers": [
            { "id": "bg", "type": "background", "paint": { "background-color": "#000000" } }
          ]
        }
        """
          .trimIndent()
      )
  }
}
