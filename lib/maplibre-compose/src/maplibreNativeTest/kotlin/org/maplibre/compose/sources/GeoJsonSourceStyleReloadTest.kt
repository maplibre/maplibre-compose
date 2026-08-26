package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.util.onMap
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

/**
 * `setData` after a style reload. A requested style change unloads the binding before native
 * replaces the style, so a later `setData` is dropped. A native reload that invalidates the source
 * first throws [MaplibreException], which Kotlin can catch — unlike the ObjC `NSException` that
 * terminated the process on iOS (#835).
 */
class GeoJsonSourceStyleReloadTest {

  @Test
  fun setData_after_a_style_switch_is_a_no_op() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertIs<MlnFfiStyleBinding>(fixture.style, "Errors: ${fixture.errors}")
      val source = GeoJsonSource(SOURCE_ID, GeoJsonData.Features(pointAt(0.0)), GeoJsonOptions())
      style.addSource(source)
      assertTrue(source.isAttached)
      source.setData(GeoJsonData.Features(pointAt(1.0)))

      fixture.loadStyle(REPLACEMENT_STYLE)
      assertFalse(source.isAttached, "the outgoing style should have unloaded the source")
      source.setData(GeoJsonData.Features(pointAt(2.0)))
      assertEquals(emptyList(), fixture.errors, "the map should report nothing")
    }
  }

  @Test
  fun setData_on_a_source_invalidated_by_a_native_style_load_throws_a_catchable_error() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertIs<MlnFfiStyleBinding>(fixture.style, "Errors: ${fixture.errors}")
      val source = GeoJsonSource(SOURCE_ID, GeoJsonData.Features(pointAt(0.0)), GeoJsonOptions())
      style.addSource(source)

      source.onMap { map ->
        map.setStyleJson(REPLACEMENT_STYLE_JSON.encodeToByteArray())
        // Native drops the source as soon as setStyleJson returns. MAP_STYLE_LOADED unloads the
        // Kotlin binding only after this owner-thread turn, so setData still reaches the map.
        assertFailsWith<MaplibreException> { source.setData(GeoJsonData.Features(pointAt(1.0))) }
      }
      assertEquals(emptyList(), fixture.errors, "the map should report nothing")
    }
  }

  private fun pointAt(longitude: Double): FeatureCollection<Geometry, JsonObject?> =
    buildFeatureCollection {
      addFeature(geometry = Point(Position(longitude = longitude, latitude = 0.0)))
    }

  private companion object {
    const val SOURCE_ID = "points"
    const val REPLACEMENT_STYLE_JSON = """{"version":8,"sources":{},"layers":[]}"""
    val REPLACEMENT_STYLE = BaseStyle.Json(REPLACEMENT_STYLE_JSON)
  }
}
