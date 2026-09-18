package org.maplibre.compose.layers

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.ast.scaledTextOffset
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.dp
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.get
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.GeoJsonSourceHandle
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

class SemiliteralRenderingTest {
  @Test
  fun offset_components_follow_feature_state_beyond_the_old_scale_limit(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(
          BaseStyle.Json(
            """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"black"}}]}"""
          )
        )
        fixture.state.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 1.0))
        val source =
          GeoJsonSource(
            "points",
            GeoJsonData.Features(
              buildFeatureCollection<Geometry, JsonObject?> {
                addFeature(geometry = Point(Position(0.0, 0.0))) { setId(1) }
              }
            ),
            GeoJsonOptions(),
          )
        fixture.state.style.sources.add(source)
        // Render the first offset component as a radius so pixel readback observes its value.
        val offset =
          scaledTextOffset(
            0.016f,
            -0.008f,
            feature.state("scale").asNumber(const(500f)),
            ExpressionContext.None,
          )
        val layer = CircleLayer("circle", source)
        layer.setCircleRadius(offset[0].dp.compile(ExpressionContext.None))
        layer.setCircleColor(const(Color.Red).compile(ExpressionContext.None))
        checkNotNull(fixture.style).install(layer)
        val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.sources["points"])
        val red = RgbaPixel(255, 0, 0, 255)
        val black = RgbaPixel(0, 0, 0, 255)
        fixture.pumpUntilPixel("the initial 8-pixel circle", 256, 256, red)
        fixture.pumpUntilPixel("outside the initial circle", 280, 256, black)
        handle.setFeatureState("1", buildJsonObject { put("scale", 2000) })
        // The old interpolation caps the radius at 16; this pixel requires the full 32.
        fixture.pumpUntilPixel("the 32-pixel circle", 280, 256, red)
        handle.removeFeatureState("1")
        fixture.pumpUntilPixel("the reset circle", 280, 256, black)
        assertTrue(fixture.errors.isEmpty(), fixture.errors.toString())
      }
    }
}
