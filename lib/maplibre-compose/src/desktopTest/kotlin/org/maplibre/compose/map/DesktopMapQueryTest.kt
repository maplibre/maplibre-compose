package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.desktop.HeadlessMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.SOURCE_ID_PROPERTY

/**
 * Exercises rendered-feature queries against a real, rendered map.
 *
 * A query only answers from what the render session actually rasterized, so nothing short of a real
 * GPU and a real frame can test it: a fake host returns an empty result no matter what the
 * conversion code does.
 *
 * The style is inline rather than fetched, so the test neither needs the network nor depends on
 * what a remote style happens to contain today.
 */
class DesktopMapQueryTest {

  @Test
  fun `a query at a covered point returns the feature`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      it.loadStyle(BaseStyle.Json(WORLD_POLYGON_STYLE))
      // Rendering is what populates the queryable set, so the frames matter, not just the load.
      it.pump(frames = 30)

      val features =
        it.session.queryRenderedFeatures(offset = CENTER, layerIds = null, predicate = null)

      assertTrue(features.isNotEmpty(), "Expected a hit at the map center. Errors: ${it.errors}")
      val feature = features.first()
      assertEquals("world", feature.properties?.get("name")?.jsonPrimitive?.content)
      // The source id has nowhere else to live on a GeoJSON Feature, so the conversion carries it
      // as a property; a caller distinguishing hits across sources depends on it.
      assertEquals("test", feature.properties?.get(SOURCE_ID_PROPERTY)?.jsonPrimitive?.content)
    }
  }

  @Test
  fun `a query restricted to another layer returns nothing`() {
    val fixture = HeadlessMapFixture.create()
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
  fun `a box query covering the map returns the feature`() {
    val fixture = HeadlessMapFixture.create()
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
  fun `a query before any frame returns empty rather than throwing`() {
    val fixture = HeadlessMapFixture.create()
    fixture.use {
      assertTrue(
        it.session
          .queryRenderedFeatures(offset = CENTER, layerIds = null, predicate = null)
          .isEmpty()
      )
    }
  }

  private companion object {
    /** The center of [HeadlessMapFixture.DEFAULT_EXTENT], in the logical pixels a query takes. */
    val CENTER = DpOffset(256.dp, 256.dp)

    /**
     * A polygon covering most of the world, so the center of the viewport is a hit at any zoom the
     * map might settle on.
     */
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
  }
}
