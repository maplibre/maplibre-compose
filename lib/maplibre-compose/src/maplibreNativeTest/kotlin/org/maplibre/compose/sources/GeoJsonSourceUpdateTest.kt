package org.maplibre.compose.sources

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.style.uninstall
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
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
        style.install(layer)
        val sourceHandle = assertIs<GeoJsonSourceHandle>(fixture.state.style.source(SOURCE_ID))

        fixture.pumpUntil("the initial point to be queryable") { fixture.centerHits() }

        sourceHandle.setData(GeoJsonData.Features(pointAt(FAR_AWAY)))

        // Real hosts draw only requested frames. An unconditional pump would hide a missing
        // repaint; settle draws only the frames the session asks for.
        fixture.settle()
        assertFalse(
          fixture.centerHits(),
          "the update did not leave the origin after settle: ${fixture.errors}",
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
        fixture.pumpUntil("the base-style point to be queryable") { fixture.centerHits() }

        handle.setData(GeoJsonData.Features(pointAt(FAR_AWAY)))

        fixture.settle()
        assertFalse(fixture.centerHits(), "the clustered source update stayed at the origin")
        assertEquals(emptyList(), fixture.errors)
      }
    }

  @Test
  fun a_base_style_update_preserves_the_loaded_sources_minimum_zoom(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(MIN_ZOOM_STYLE)
      fixture.presentation.setCameraPosition(CameraPosition(target = ORIGIN, zoom = 6.0))
      val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.source(SOURCE_ID))
      // settle paints the zoom-6 camera on demand. An empty query before that frame is the
      // default, not proof the minzoom hid the point.
      fixture.settle()
      assertFalse(
        fixture.centerHits(),
        "the source rendered a point below its minimum zoom: ${fixture.errors}",
      )

      handle.setData(GeoJsonData.Features(pointAt(ORIGIN)))

      fixture.settle()
      assertFalse(
        fixture.centerHits(),
        "setData rendered a point below the loaded minzoom: ${fixture.errors}",
      )
      assertEquals(emptyList(), fixture.errors)
    }
  }

  @Test
  fun a_cached_handle_uses_reconciled_options_after_a_same_id_replacement(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(STYLE)
        fixture.presentation.setCameraPosition(CameraPosition(target = ORIGIN, zoom = 14.0))
        val style = checkNotNull(fixture.style)
        val first =
          GeoJsonSource(
            SOURCE_ID,
            GeoJsonData.Features(pointAt(ORIGIN)),
            GeoJsonOptions(),
          )
        style.install(first)
        val layer = CircleLayer(LAYER_ID, first)
        layer.setCircleRadius(const(16.dp).compile(ExpressionContext.None))
        style.install(layer)
        val handle = assertIs<GeoJsonSourceHandle>(fixture.state.style.source(SOURCE_ID))
        style.uninstall(layer)
        style.removeSource(SOURCE_ID)
        style.install(
          GeoJsonSource(
            SOURCE_ID,
            GeoJsonData.Features(pointAt(FAR_AWAY)),
            GeoJsonOptions(cluster = true, clusterRadius = 123, clusterMaxZoom = 10),
          )
        )
        style.install(layer)

        handle.setData(GeoJsonData.Features(pointAt(ORIGIN)))

        fixture.settle()
        assertTrue(
          fixture.centerHits(),
          "the cached handle did not write into the replacement source: ${fixture.errors}",
        )
        assertEquals(emptyList(), fixture.errors)
      }
    }

  private suspend fun MapFixture.centerHits(): Boolean =
    presentation.queryRenderedFeatures(offset = CENTER, layerIds = setOf(LAYER_ID)).isNotEmpty()

  private fun pointAt(position: Position): FeatureCollection<Geometry, JsonObject?> =
    buildFeatureCollection {
      addFeature(geometry = Point(position))
    }

  private companion object {
    const val SOURCE_ID = "points"
    const val LAYER_ID = "points-layer"
    val ORIGIN = Position(0.0, 0.0)
    val FAR_AWAY = Position(5.0, 5.0)
    val CENTER = DpOffset(256.dp, 256.dp)
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
