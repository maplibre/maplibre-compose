package org.maplibre.compose.style

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.meters
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.SnapshotStyleOwnership
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.declare
import org.maplibre.compose.testing.pumpUntilPixel
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

class StyleGroundScaleTest {
  @Test
  fun meters_follow_zoom_in_the_renderer_and_latitude_from_the_declared_camera(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        val source =
          GeoJsonSource(
            "points",
            GeoJsonData.Features(
              buildFeatureCollection<Geometry, JsonObject?> {
                addFeature(geometry = Point(Position(0.0, 60.0)))
                addFeature(geometry = Point(Position(0.0, 0.0)))
              }
            ),
          )
        suspend fun declare() {
          fixture.declare(ownership = SnapshotStyleOwnership(setOf("points"), setOf("circle"))) {
            // 24 dp at zoom 10 and latitude 60, where one dp covers about 38.2 meters.
            CircleLayer("circle", source, radius = const(917f).meters, color = const(Color.Red))
          }
        }
        // The declaration reads the viewport the engine reports, which follows the camera command.
        suspend fun awaitLatitude(latitude: Double) =
          fixture.pumpUntil("the viewport at latitude $latitude") {
            fixture.session.getViewport()?.cameraPosition?.center?.latitude?.let {
              abs(it - latitude) < 1e-6
            } == true
          }
        fixture.loadStyle(BlackStyle)
        fixture.state.setCameraPosition(CameraPosition(center = Position(0.0, 60.0), zoom = 10.0))
        awaitLatitude(60.0)
        declare()
        fixture.pumpUntilPixel("inside 24 dp at latitude 60", 276, 256, Red)
        fixture.pumpUntilPixel("outside 24 dp at latitude 60", 284, 256, Black)

        fixture.state.setCameraPosition(CameraPosition(center = Position(0.0, 60.0), zoom = 11.0))
        fixture.pumpUntilPixel("inside 48 dp after zooming in", 300, 256, Red)
        fixture.pumpUntilPixel("outside 48 dp after zooming in", 308, 256, Black)

        // At the equator one dp covers twice the meters, so the same circle halves once the
        // declaration reads the new camera.
        fixture.state.setCameraPosition(CameraPosition(center = Position(0.0, 0.0), zoom = 11.0))
        awaitLatitude(0.0)
        declare()
        fixture.pumpUntilPixel("inside 24 dp at the equator", 276, 256, Red)
        fixture.pumpUntilPixel("outside 24 dp at the equator", 284, 256, Black)
        assertEquals(emptyList(), fixture.errors.toList())
      }
    }

  private companion object {
    val Red = RgbaPixel(255, 0, 0, 255)
    val Black = RgbaPixel(0, 0, 0, 255)
    val BlackStyle =
      BaseStyle.Json(
        """{"version":8,"transition":{"duration":0},"sources":{},"layers":[
        {"id":"background","type":"background","paint":{"background-color":"black"}}
      ]}"""
      )
  }
}
