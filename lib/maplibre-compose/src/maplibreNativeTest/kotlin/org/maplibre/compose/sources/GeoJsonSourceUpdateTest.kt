package org.maplibre.compose.sources

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayerDescriptor
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

/**
 * Renders one point at the screen center, then replaces the source's data with a point far
 * offscreen. The center pixel must return to the background, which only happens when the update
 * reaches native, native re-tiles, and a frame presents the result.
 */
class GeoJsonSourceUpdateTest {

  @Test fun setData_moves_a_rendered_point() = assertUpdateMovesPoint(synchronousUpdate = false)

  @Test
  fun setData_moves_a_rendered_point_with_synchronous_tiling() =
    assertUpdateMovesPoint(synchronousUpdate = true)

  @Test
  fun setData_moves_a_rendered_point_when_frames_are_on_demand() =
    assertUpdateMovesPoint(synchronousUpdate = false, onDemand = true)

  /** The batch number the installed data was published with; every feature shares it. */
  private fun installedBatch(source: GeoJsonSource): Double {
    val features = (source.toJson()["data"] as JsonObject)["features"] as JsonArray
    val first = features.firstOrNull() as? JsonObject ?: return Double.NaN
    val coordinates = (first["geometry"] as JsonObject)["coordinates"] as JsonArray
    return coordinates[0].jsonPrimitive.double
  }

  private fun manyPoints(longitude: Double): FeatureCollection<Geometry, JsonObject?> =
    buildFeatureCollection {
      repeat(STREAM_POINT_COUNT) { index ->
        addFeature(geometry = Point(Position(longitude, latitude = index * 0.0001)))
      }
    }

  private fun assertUpdateMovesPoint(synchronousUpdate: Boolean, onDemand: Boolean = false) =
    runBlocking {
      BridgeMapFixture.create().use { fixture ->
        fixture.loadStyle(STYLE)
        val style = assertIs<MlnFfiStyleBinding>(fixture.style, "Errors: ${fixture.errors}")
        fixture.core.setCameraPosition(CameraPosition(target = ORIGIN, zoom = 14.0))

        val source =
          GeoJsonSource(
            SOURCE_ID,
            GeoJsonData.Features(pointAt(ORIGIN)),
            GeoJsonOptions(synchronousUpdate = synchronousUpdate),
          )
        style.addSource(source)
        val layer = CircleLayerDescriptor(LAYER_ID, source)
        layer.setCircleRadius(const(16.dp).compile(ExpressionContext.None))
        layer.setCircleColor(const(Color.Black))
        layer.setCircleOpacity(const(1.0f))
        style.addLayer(layer)

        val extent = BridgeMapFixture.DEFAULT_EXTENT
        val centerX = extent.physicalWidth / 2
        val centerY = extent.physicalHeight / 2

        fixture.pumpUntil("the initial point to render", PUMP_TIMEOUT) {
          fixture.hasRendered && fixture.readPixel(centerX, centerY).isNear(CIRCLE)
        }

        source.setData(GeoJsonData.Features(pointAt(FAR_AWAY)))
        source.applyData(style)

        if (onDemand) {
          // The real hosts draw only the frames the session asks for. An unconditional pump would
          // mask an update that never turns into a frame request.
          fixture.settle()
          assertTrue(
            fixture.readPixel(centerX, centerY).isNear(BACKGROUND),
            "the update did not render without pumping: ${fixture.errors}",
          )
        } else {
          fixture.pumpUntil("the update to render", PUMP_TIMEOUT) {
            fixture.hasRendered && fixture.readPixel(centerX, centerY).isNear(BACKGROUND)
          }
        }
        assertEquals(emptyList(), fixture.errors, "the map should report nothing")
      }
    }

  private fun pointAt(position: Position): FeatureCollection<Geometry, JsonObject?> =
    buildFeatureCollection {
      addFeature(geometry = Point(position))
    }

  private fun RgbaPixel.isNear(expected: RgbaPixel): Boolean =
    listOf(
        abs(expected.red - red),
        abs(expected.green - green),
        abs(expected.blue - blue),
        abs(expected.alpha - alpha),
      )
      .all { it <= CHANNEL_TOLERANCE }

  private companion object {
    const val SOURCE_ID = "points"
    const val LAYER_ID = "points-layer"
    const val CHANNEL_TOLERANCE = 2

    /** Matches the GeoJSON benchmark: 8000 points, one publication per frame. */
    const val STREAM_POINT_COUNT = 8_000

    const val FRAME_INTERVAL_MILLIS = 16L
    const val POLL_INTERVAL_MILLIS = 250L

    val STREAM_TIMEOUT = 30.seconds
    val PUMP_TIMEOUT = 30.seconds

    val ORIGIN = Position(longitude = 0.0, latitude = 0.0)

    /** At zoom 14 this is far beyond the viewport. */
    val FAR_AWAY = Position(longitude = 5.0, latitude = 5.0)

    val BACKGROUND = RgbaPixel(red = 0x33, green = 0x66, blue = 0x99, alpha = 0xff)
    val CIRCLE = RgbaPixel(red = 0x00, green = 0x00, blue = 0x00, alpha = 0xff)

    val STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"#336699"}}
        ]}
        """
      )
  }
}
