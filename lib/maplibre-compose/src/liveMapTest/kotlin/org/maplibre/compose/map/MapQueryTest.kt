package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.Feature
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Feature as GeoJsonFeature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

class MapQueryTest {

  @Test
  fun a_query_at_a_covered_point_returns_the_feature(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Json(WORLD_POLYGON_STYLE))
      // Rendering, not loading, is what populates the queryable set.
      it.pump(frames = 30)

      val features = it.presentation.queryRenderedFeatures(offset = CENTER)

      assertTrue(features.isNotEmpty(), "Expected a hit at the map center. Errors: ${it.errors}")
      val feature = features.first()
      assertEquals(buildJsonObject { put("name", "world") }, feature.properties)
    }
  }

  @Test
  fun queried_feature_metadata_does_not_replace_source_properties(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Json(COLLIDING_PROPERTIES_STYLE))
      it.pump(frames = 30)

      val feature = it.presentation.queryRenderedFeatures(offset = CENTER).first()

      assertEquals("original-source", feature.properties?.get("\$source")?.jsonPrimitive?.content)
      assertEquals(
        "original-source-layer",
        feature.properties?.get("\$sourceLayer")?.jsonPrimitive?.content,
      )
      assertEquals("original-state", feature.properties?.get("\$state")?.jsonPrimitive?.content)
    }
  }

  @Test
  fun a_query_restricted_to_another_layer_returns_nothing(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Json(WORLD_POLYGON_STYLE))
      it.pump(frames = 30)

      val features =
        it.presentation.queryRenderedFeatures(
          offset = CENTER,
          layerIds = setOf("no-such-layer"),
        )

      assertTrue(features.isEmpty(), "Expected no hits when filtering to a layer that is not there")
    }
  }

  @Test
  fun a_query_while_a_style_loads_is_not_a_load_failure(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Json(WORLD_POLYGON_STYLE))
      it.pump(frames = 5)

      // Not awaited: the query below must land inside the loading window.
      it.session.setBaseStyle(BaseStyle.Json(EMPTY_STYLE))
      it.presentation.queryRenderedFeatures(
        offset = CENTER,
        layerIds = setOf("no-such-layer"),
      )
      it.pump(frames = 30)

      assertTrue(
        it.errors.isEmpty(),
        "Naming a layer the style does not have is an ordinary query, not the map failing to " +
          "load. Got: ${it.errors}",
      )
    }
  }

  @Test
  fun a_box_query_covering_the_map_returns_the_feature(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Json(WORLD_POLYGON_STYLE))
      it.pump(frames = 30)

      val features =
        it.presentation.queryRenderedFeatures(
          rect = DpRect(left = 0.dp, top = 0.dp, right = 512.dp, bottom = 512.dp)
        )

      assertTrue(features.isNotEmpty(), "Expected a hit somewhere in the viewport")
    }
  }

  @Test
  fun a_query_before_any_frame_waits_for_the_first_viewport(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      coroutineScope {
        val query =
          async(start = CoroutineStart.UNDISPATCHED) {
            fixture.presentation.queryRenderedFeatures(offset = CENTER)
          }

        assertFalse(query.isCompleted)
        fixture.pumpUntil("the first viewport to make the query available") { query.isCompleted }

        assertTrue(query.await().isEmpty())
      }
    }
  }

  @Test
  fun a_predicate_keeps_only_matching_features(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Json(WORLD_POLYGON_STYLE))
      it.pump(frames = 30)

      val matching = Feature["name"].cast<StringValue>() eq const("world")
      val misses = Feature["name"].cast<StringValue>() eq const("other")

      val kept = it.presentation.queryRenderedFeatures(offset = CENTER, predicate = matching)
      assertTrue(kept.isNotEmpty(), "Expected the matching predicate to keep the feature")
      assertEquals("world", kept.first().properties?.get("name")?.jsonPrimitive?.content)

      val dropped = it.presentation.queryRenderedFeatures(offset = CENTER, predicate = misses)
      assertTrue(dropped.isEmpty(), "Expected the non-matching predicate to drop the feature")
    }
  }

  @Test
  fun a_query_returns_the_front_layer_first(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Json(OVERLAPPING_FILL_STYLE))
      it.pump(frames = 30)

      val features = it.presentation.queryRenderedFeatures(offset = CENTER)
      val names = features.map { feature ->
        feature.properties?.get("name")?.jsonPrimitive?.content
      }

      assertTrue(
        names.contains("front") && names.contains("back"),
        "Expected both layers. Got $names",
      )
      assertEquals("front", names.first(), "The feature in front should be first. Got $names")
      assertTrue(
        names.indexOf("front") < names.indexOf("back"),
        "Front should precede back in render order. Got $names",
      )
    }
  }

  @Test
  fun a_query_at_an_off_center_point_returns_only_the_feature_there(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Json(TWO_HALVES_STYLE))
      // Zoom 0 keeps ±90 inside the 512 px viewport.
      it.presentation.setCameraPosition(CameraPosition(target = Position(0.0, 0.0), zoom = 0.0))
      it.pump(frames = 30)

      val westAt = assertNotNull(it.presentation.screenLocationFromPosition(WEST_POINT))
      val eastAt = assertNotNull(it.presentation.screenLocationFromPosition(EAST_POINT))
      val westHits = it.presentation.queryRenderedFeatures(offset = westAt)
      val eastHits = it.presentation.queryRenderedFeatures(offset = eastAt)

      assertEquals(
        setOf("west"),
        westHits.names(),
        "Expected only west. Hits: ${westHits.names()}",
      )
      assertEquals(
        setOf("east"),
        eastHits.names(),
        "Expected only east. Hits: ${eastHits.names()}",
      )
    }
  }

  @Test
  fun a_queried_feature_keeps_its_geojson_id(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Json(WORLD_POLYGON_STYLE))
      it.pump(frames = 30)

      val feature = it.presentation.queryRenderedFeatures(offset = CENTER).first()
      val id = assertIs<JsonPrimitive>(feature.id)
      assertFalse(id.isString, "Expected the GeoJSON id to stay a number, not a string")
      assertEquals("42", id.content)
    }
  }

  private companion object {
    /** The center of [MapFixture.DEFAULT_EXTENT], in the logical pixels a query takes. */
    val CENTER = DpOffset(256.dp, 256.dp)

    val WEST_POINT = Position(longitude = -90.0, latitude = 0.0)

    val EAST_POINT = Position(longitude = 90.0, latitude = 0.0)

    fun List<GeoJsonFeature<Geometry, JsonObject?>>.names(): Set<String?> = map { feature ->
      feature.properties?.get("name")?.jsonPrimitive?.content
    }
      .toSet()

    /**
     * Two non-overlapping fills, one west and one east of the prime meridian. A point query at
     * [WEST_POINT] or [EAST_POINT] hits only that half.
     */
    val TWO_HALVES_STYLE =
      """
      {
        "version": 8,
        "name": "query-halves-test",
        "sources": {
          "test": {
            "type": "geojson",
            "data": {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "id": 1,
                  "properties": { "name": "west" },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [
                      [[-170, -80], [-10, -80], [-10, 80], [-170, 80], [-170, -80]]
                    ]
                  }
                },
                {
                  "type": "Feature",
                  "id": 2,
                  "properties": { "name": "east" },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [
                      [[10, -80], [170, -80], [170, 80], [10, 80], [10, -80]]
                    ]
                  }
                }
              ]
            }
          }
        },
        "layers": [
          { "id": "test-fill", "type": "fill", "source": "test", "paint": { "fill-color": "#ff0000" } }
        ]
      }
      """
        .trimIndent()

    /** A polygon covering most of the world, so the viewport center is a hit at any zoom. */
    val WORLD_POLYGON_STYLE =
      """
      {
        "version": 8,
        "name": "query-test",
        "sources": {
          "test": {
            "type": "geojson",
            "data": {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "id": 42,
                  "properties": { "name": "world" },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [
                      [[-170, -80], [170, -80], [170, 80], [-170, 80], [-170, -80]]
                    ]
                  }
                }
              ]
            }
          }
        },
        "layers": [
          { "id": "test-fill", "type": "fill", "source": "test", "paint": { "fill-color": "#ff0000" } }
        ]
      }
      """
        .trimIndent()

    val EMPTY_STYLE =
      """{ "version": 8, "name": "empty", "sources": {}, "layers": [] }""".trimIndent()

    val COLLIDING_PROPERTIES_STYLE =
      WORLD_POLYGON_STYLE.replace(
        """"properties": { "name": "world" }""",
        """"properties": {"name":"world","${'$'}source":"original-source","${'$'}sourceLayer":"original-source-layer","${'$'}state":"original-state"}""",
      )

    /**
     * Two world-covering fills from different sources. The second layer is the one in front. The
     * query API promises that order: front first, then back.
     */
    val OVERLAPPING_FILL_STYLE =
      """
      {
        "version": 8,
        "name": "query-order-test",
        "sources": {
          "back": {
            "type": "geojson",
            "data": {
              "type": "Feature",
              "properties": { "name": "back" },
              "geometry": {
                "type": "Polygon",
                "coordinates": [
                  [[-170, -80], [170, -80], [170, 80], [-170, 80], [-170, -80]]
                ]
              }
            }
          },
          "front": {
            "type": "geojson",
            "data": {
              "type": "Feature",
              "properties": { "name": "front" },
              "geometry": {
                "type": "Polygon",
                "coordinates": [
                  [[-170, -80], [170, -80], [170, 80], [-170, 80], [-170, -80]]
                ]
              }
            }
          }
        },
        "layers": [
          { "id": "back-fill", "type": "fill", "source": "back", "paint": { "fill-color": "#0000ff" } },
          { "id": "front-fill", "type": "fill", "source": "front", "paint": { "fill-color": "#ff0000" } }
        ]
      }
      """
        .trimIndent()
  }
}
