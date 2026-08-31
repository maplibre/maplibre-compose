package org.maplibre.compose.sources

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RgbaPixel
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

/** Sensitive engine coverage for an imperative GeoJSON update after installation. */
class GeoJsonSourceUpdateTest {
  @Test
  fun an_update_moves_a_rendered_point_and_requests_an_on_demand_frame(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(STYLE)
        fixture.presentation.setCameraPosition(CameraPosition(target = ORIGIN, zoom = 14.0))
        val style = checkNotNull(fixture.style) { "Errors: ${fixture.errors}" }
        val source =
          GeoJsonSource(
            SOURCE_ID,
            GeoJsonData.Features(pointAt(ORIGIN)),
            GeoJsonOptions(),
          )
        style.install(source)
        val layer = CircleLayer(LAYER_ID, source)
        layer.setCircleRadius(const(16.dp).compile(ExpressionContext.None))
        layer.setCircleColor(const(Color.Black))
        layer.setCircleOpacity(const(1.0f))
        style.install(layer)
        val sourceHandle = assertIs<GeoJsonSourceHandle>(fixture.state.style.source(SOURCE_ID))

        val centerX = 256
        val centerY = 256
        fixture.pumpUntil("the initial point to render") {
          fixture.readPixel(centerX, centerY).isNear(CIRCLE)
        }

        sourceHandle.setData(GeoJsonData.Features(pointAt(FAR_AWAY)))

        // Real hosts draw only requested frames. No unconditional pump may mask a missing repaint.
        fixture.settle()
        assertTrue(
          fixture.readPixel(centerX, centerY).isNear(BACKGROUND),
          "the update did not render without pumping: ${fixture.errors}",
        )
        assertEquals(emptyList(), fixture.errors, "the map should report nothing")
      }
    }

  @Test
  fun a_base_style_update_uses_the_loaded_sources_non_default_options(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(CLUSTERED_STYLE)
        fixture.presentation.setCameraPosition(CameraPosition(target = ORIGIN, zoom = 14.0))
        val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.source(SOURCE_ID))
        fixture.pumpUntil("the base-style point to render") {
          fixture.readPixel(256, 256).isNear(CIRCLE)
        }

        handle.setData(GeoJsonData.Features(pointAt(FAR_AWAY)))

        fixture.settle()
        assertTrue(fixture.readPixel(256, 256).isNear(BACKGROUND))
        assertEquals(emptyList(), fixture.errors)
      }
    }

  @Test
  fun a_base_style_update_preserves_the_loaded_sources_minimum_zoom(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(MIN_ZOOM_STYLE)
      fixture.presentation.setCameraPosition(CameraPosition(target = ORIGIN, zoom = 6.0))
      val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.source(SOURCE_ID))
      fixture.pumpUntil("the source to stay hidden below its minimum zoom") {
        fixture.readPixel(256, 256).isNear(BACKGROUND)
      }

      handle.setData(GeoJsonData.Features(pointAt(ORIGIN)))

      fixture.settle()
      assertTrue(fixture.readPixel(256, 256).isNear(BACKGROUND))
      assertEquals(emptyList(), fixture.errors)
    }
  }

  @Test
  fun a_cached_handle_uses_reconciled_options_after_a_same_id_replacement(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(STYLE)
        val style = checkNotNull(fixture.style)
        style.install(
          GeoJsonSource(
            SOURCE_ID,
            GeoJsonData.Features(pointAt(ORIGIN)),
            GeoJsonOptions(),
          )
        )
        val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.source(SOURCE_ID))
        style.removeSource(SOURCE_ID)
        style.install(
          GeoJsonSource(
            SOURCE_ID,
            GeoJsonData.Features(pointAt(ORIGIN)),
            GeoJsonOptions(cluster = true, clusterRadius = 123, clusterMaxZoom = 10),
          )
        )

        handle.setData(GeoJsonData.Features(pointAt(FAR_AWAY)))

        assertEquals(emptyList(), fixture.errors)
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
    val CLUSTERED_STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{"points":{"type":"geojson","data":{
          "type":"FeatureCollection","features":[{"type":"Feature","geometry":{
            "type":"Point","coordinates":[0,0]},"properties":{}}]},
          "cluster":true,"clusterRadius":123,"clusterMaxZoom":10}},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"#336699"}},
          {"id":"points-layer","type":"circle","source":"points","paint":{
            "circle-radius":16,"circle-color":"#000000"}}]}
        """
          .trimIndent()
      )
    val MIN_ZOOM_STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{"points":{"type":"geojson","minzoom":7,"data":{
          "type":"FeatureCollection","features":[{"type":"Feature","geometry":{
            "type":"Point","coordinates":[0,0]},"properties":{}}]}}},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"#336699"}},
          {"id":"points-layer","type":"circle","source":"points","paint":{
            "circle-radius":16,"circle-color":"#000000"}}]}
        """
          .trimIndent()
      )
  }
}
