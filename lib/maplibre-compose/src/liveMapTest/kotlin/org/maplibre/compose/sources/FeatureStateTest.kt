package org.maplibre.compose.sources

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
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
  fun a_geojson_handle_updates_feature_state_and_the_rendered_style(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BLACK_STYLE)
      fixture.state.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 1.0))
      val binding = checkNotNull(fixture.style)
      val source =
        GeoJsonSource(
          id = "points",
          data =
            GeoJsonData.Features(
              buildFeatureCollection<Geometry, JsonObject?> {
                addFeature(geometry = Point(Position(0.0, 0.0))) { setId(1) }
              }
            ),
          options = GeoJsonOptions(),
        )
      fixture.state.style.sources.add(source)
      val layer = CircleLayer("circles", source)
      layer.setCircleRadius(const(48.dp).compile(ExpressionContext.None))
      layer.setCircleColor(
        switch(
            condition(
              feature.state<BooleanValue>("selected").asBoolean(const(false)),
              const(Color.Red),
            ),
            fallback = const(Color.Blue),
          )
          .compile(ExpressionContext.None)
      )
      binding.install(layer)
      val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["points"])

      fixture.pumpUntilPixel("the default circle", CENTER, CENTER, BLUE)
      handle.setFeatureState(
        "1",
        buildJsonObject {
          put("selected", true)
          put("rank", 1)
        },
      )
      handle.setFeatureState("1", buildJsonObject { put("label", "chosen") })
      assertEquals(true, handle.getFeatureState("1")["selected"]?.jsonPrimitive?.boolean)
      assertEquals(1, handle.getFeatureState("1")["rank"]?.jsonPrimitive?.content?.toInt())
      assertEquals("chosen", handle.getFeatureState("1")["label"]?.jsonPrimitive?.content)
      fixture.pumpUntilPixel("the selected circle", CENTER, CENTER, RED)

      handle.removeFeatureState("1", "rank")
      assertEquals(null, handle.getFeatureState("1")["rank"])
      assertEquals(true, handle.getFeatureState("1")["selected"]?.jsonPrimitive?.boolean)
      handle.removeFeatureState("1")
      assertEquals(JsonObject(emptyMap()), handle.getFeatureState("1"))
      handle.setFeatureState("1", buildJsonObject { put("selected", true) })
      handle.resetFeatureStates()
      fixture.pumpUntilPixel("the reset circle", CENTER, CENTER, BLUE)
      assertEquals(JsonObject(emptyMap()), handle.getFeatureState("1"))
    }
  }

  private companion object {
    const val CENTER = 256
    val RED = RgbaPixel(255, 0, 0, 255)
    val BLUE = RgbaPixel(0, 0, 255, 255)
    val BLACK_STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"#000000"}}
        ]}
        """
          .trimIndent()
      )
  }
}
