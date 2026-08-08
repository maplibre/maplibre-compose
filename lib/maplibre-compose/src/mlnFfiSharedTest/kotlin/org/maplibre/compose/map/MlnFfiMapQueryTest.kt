package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.SOURCE_ID_PROPERTY

/**
 * Exercises rendered-feature queries against a real, rendered map. A query only answers from what
 * the render session actually rasterized, so a fake host returns an empty result no matter what the
 * conversion code does.
 */
class MlnFfiMapQueryTest {

  @Test
  fun a_query_at_a_covered_point_returns_the_feature() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(WORLD_POLYGON_STYLE))
      // Rendering, not loading, is what populates the queryable set.
      it.pump(frames = 30)

      val features =
        it.session.queryRenderedFeatures(offset = CENTER, layerIds = null, predicate = null)

      assertTrue(features.isNotEmpty(), "Expected a hit at the map center. Errors: ${it.errors}")
      val feature = features.first()
      assertEquals("world", feature.properties?.get("name")?.jsonPrimitive?.content)
      // The source id has nowhere else to live on a GeoJSON Feature, so it rides as a property.
      assertEquals("test", feature.properties?.get(SOURCE_ID_PROPERTY)?.jsonPrimitive?.content)
    }
  }

  @Test
  fun queried_feature_metadata_does_not_replace_source_properties() {
    BridgeMapFixture.create().use {
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
  fun a_query_restricted_to_another_layer_returns_nothing() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
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
  fun a_box_query_covering_the_map_returns_the_feature() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
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

  /** Queried before the first frame, which is what a click during startup does. */
  @Test
  fun a_query_before_any_frame_returns_empty_rather_than_throwing() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      assertTrue(
        it.session
          .queryRenderedFeatures(offset = CENTER, layerIds = null, predicate = null)
          .isEmpty()
      )
    }
  }

  private companion object {
    /** The center of [BridgeMapFixture.DEFAULT_EXTENT], in the logical pixels a query takes. */
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

    val COLLIDING_PROPERTIES_STYLE =
      WORLD_POLYGON_STYLE.replace(
        """"properties": { "name": "world" }""",
        """"properties": {"name":"world","${'$'}source":"original-source","${'$'}sourceLayer":"original-source-layer","${'$'}state":"original-state"}""",
      )
  }
}
