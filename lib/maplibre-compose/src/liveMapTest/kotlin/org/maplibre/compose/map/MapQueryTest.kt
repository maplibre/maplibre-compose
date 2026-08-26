package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.Feature
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapFixture
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class MapQueryTest {

  @Test
  fun a_query_at_a_covered_point_returns_the_feature(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Json(WORLD_POLYGON_STYLE))
      // Rendering, not loading, is what populates the queryable set.
      it.pump(frames = 30)

      val features =
        it.session.queryRenderedFeatures(offset = CENTER, layerIds = null, predicate = null)

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

      val feature =
        it.session.queryRenderedFeatures(offset = CENTER, layerIds = null, predicate = null).first()

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
        it.session.queryRenderedFeatures(
          offset = CENTER,
          layerIds = setOf("no-such-layer"),
          predicate = null,
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
      it.session.queryRenderedFeatures(
        offset = CENTER,
        layerIds = setOf("no-such-layer"),
        predicate = null,
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
        it.session.queryRenderedFeatures(
          rect = DpRect(left = 0.dp, top = 0.dp, right = 512.dp, bottom = 512.dp),
          layerIds = null,
          predicate = null,
        )

      assertTrue(features.isNotEmpty(), "Expected a hit somewhere in the viewport")
    }
  }

  @Test
  fun a_query_before_any_frame_returns_empty_rather_than_throwing(): MapTestResult = runMapTest {
    createMapFixture().use {
      assertTrue(
        it.session
          .queryRenderedFeatures(offset = CENTER, layerIds = null, predicate = null)
          .isEmpty()
      )
    }
  }

  @Test
  fun a_predicate_keeps_only_matching_features(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Json(WORLD_POLYGON_STYLE))
      it.pump(frames = 30)

      val matching =
        (Feature["name"].cast<StringValue>() eq const("world")).compile(ExpressionContext.None)
      val misses =
        (Feature["name"].cast<StringValue>() eq const("other")).compile(ExpressionContext.None)

      val kept =
        it.session.queryRenderedFeatures(offset = CENTER, layerIds = null, predicate = matching)
      assertTrue(kept.isNotEmpty(), "Expected the matching predicate to keep the feature")
      assertEquals("world", kept.first().properties?.get("name")?.jsonPrimitive?.content)

      val dropped =
        it.session.queryRenderedFeatures(offset = CENTER, layerIds = null, predicate = misses)
      assertTrue(dropped.isEmpty(), "Expected the non-matching predicate to drop the feature")
    }
  }

  @Test
  fun a_query_returns_the_front_layer_first(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Json(OVERLAPPING_FILL_STYLE))
      it.pump(frames = 30)

      val features =
        it.session.queryRenderedFeatures(offset = CENTER, layerIds = null, predicate = null)
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
  fun a_queried_feature_keeps_its_geojson_id(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(BaseStyle.Json(WORLD_POLYGON_STYLE))
      it.pump(frames = 30)

      val feature =
        it.session.queryRenderedFeatures(offset = CENTER, layerIds = null, predicate = null).first()
      val id = assertIs<JsonPrimitive>(feature.id)
      assertFalse(id.isString, "Expected the GeoJSON id to stay a number, not a string")
      assertEquals("42", id.content)
    }
  }

  private companion object {
    /** The center of [MapFixture.DEFAULT_EXTENT], in the logical pixels a query takes. */
    val CENTER = DpOffset(256.dp, 256.dp)

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
     * Two world-covering fills from different sources. The second layer is the one in front, so an
     * unfiltered query at the centre should list `front` before `back`.
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
