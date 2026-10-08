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
import org.maplibre.compose.expressions.ast.compile
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.layers.asLayerProperty
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
      fixture.loadStyle(BlackStyle)
      fixture.state.setCameraPosition(CameraPosition(center = Position(0.0, 0.0), zoom = 1.0))
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
        )
      fixture.state.style.sources.add(source)
      val layer = TestLayer("circles", "circle", source)
      layer.paint("circle-radius", (const(48.dp).compile(ExpressionContext.None)).asLayerProperty())
      layer.paint(
        "circle-color",
        (switch(
              condition(
                feature.state("selected").asBoolean(const(false)),
                const(Color.Red),
              ),
              fallback = const(Color.Blue),
            )
            .compile(ExpressionContext.None))
          .asLayerProperty(),
      )
      binding.install(layer)
      val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["points"])

      fixture.pumpUntilPixel("the default circle", Center, Center, Blue)
      handle.setFeatureState(
        "1",
        buildJsonObject {
          put("selected", true)
          put("rank", 1)
        },
      )
      handle.setFeatureState("1", buildJsonObject { put("label", "chosen") })
      assertEquals(true, handle.getFeatureState("1")?.get("selected")?.jsonPrimitive?.boolean)
      assertEquals(1, handle.getFeatureState("1")?.get("rank")?.jsonPrimitive?.content?.toInt())
      assertEquals("chosen", handle.getFeatureState("1")?.get("label")?.jsonPrimitive?.content)
      fixture.pumpUntilPixel("the selected circle", Center, Center, Red)

      handle.removeFeatureState("1", "rank")
      assertEquals(null, handle.getFeatureState("1")?.get("rank"))
      assertEquals(true, handle.getFeatureState("1")?.get("selected")?.jsonPrimitive?.boolean)
      handle.removeFeatureState("1")
      assertEquals(JsonObject(emptyMap()), handle.getFeatureState("1"))
      handle.setFeatureState("1", buildJsonObject { put("selected", true) })
      handle.resetFeatureStates()
      fixture.pumpUntilPixel("the reset circle", Center, Center, Blue)
      assertEquals(JsonObject(emptyMap()), handle.getFeatureState("1"))
    }
  }

  private companion object {
    const val Center = 256
    val Red = RgbaPixel(255, 0, 0, 255)
    val Blue = RgbaPixel(0, 0, 255, 255)
    val BlackStyle =
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
