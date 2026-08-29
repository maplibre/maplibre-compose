package org.maplibre.compose.sources

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

/** Sensitive engine coverage for a declarative GeoJSON revision after installation. */
class GeoJsonSourceUpdateTest {
  @Test
  fun an_update_moves_a_rendered_point_and_requests_an_on_demand_frame() = runBlocking {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(STYLE)
      fixture.session.setCameraPosition(CameraPosition(target = ORIGIN, zoom = 14.0))
      val style = assertIs<MlnFfiStyleBinding>(fixture.style, "Errors: ${fixture.errors}")
      val source =
        GeoJsonSource(
          SOURCE_ID,
          GeoJsonData.Features(pointAt(ORIGIN)),
          GeoJsonOptions(),
        )
      val sourceHandle = style.install(source)
      val layer = CircleLayer(LAYER_ID, source)
      layer.setCircleRadius(const(16.dp).compile(ExpressionContext.None))
      layer.setCircleColor(const(Color.Black))
      layer.setCircleOpacity(const(1.0f))
      style.install(layer)

      val centerX = BridgeMapFixture.DEFAULT_EXTENT.physicalWidth / 2
      val centerY = BridgeMapFixture.DEFAULT_EXTENT.physicalHeight / 2
      fixture.pumpUntil("the initial point to render", 30.seconds) {
        fixture.hasRendered && fixture.readPixel(centerX, centerY).isNear(CIRCLE)
      }

      source.setDesiredData(GeoJsonData.Features(pointAt(FAR_AWAY)))
      sourceHandle.update(source.definition())

      // Real hosts draw only requested frames. No unconditional pump may mask a missing repaint.
      fixture.settle()
      assertTrue(
        fixture.readPixel(centerX, centerY).isNear(BACKGROUND),
        "the update did not render without pumping: ${fixture.errors}",
      )
      assertEquals(emptyList(), fixture.errors, "the map should report nothing")
    }
  }

  private fun pointAt(position: Position): FeatureCollection<Geometry, JsonObject?> =
    buildFeatureCollection {
      addFeature(geometry = Point(position))
    }

  private companion object {
    const val SOURCE_ID = "points"
    const val LAYER_ID = "points-layer"
    val ORIGIN = Position(0.0, 0.0)
    val FAR_AWAY = Position(5.0, 5.0)
    val BACKGROUND = RgbaPixel(0x33, 0x66, 0x99, 0xff)
    val CIRCLE = RgbaPixel(0x00, 0x00, 0x00, 0xff)
    val STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"#336699"}}
        ]}
        """
          .trimIndent()
      )
  }
}
