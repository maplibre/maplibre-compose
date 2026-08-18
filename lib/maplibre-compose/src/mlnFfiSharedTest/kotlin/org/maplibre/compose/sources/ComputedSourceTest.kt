package org.maplibre.compose.sources

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.RecordingList
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyle
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/**
 * Drives a computed source through a real map, since a source that is never asked and one that
 * answers on the wrong thread are only distinguishable by a rendered query.
 */
class ComputedSourceTest {

  /** Every request MapLibre made, recorded from whichever thread the source answered on. */
  private val requests = RecordingList<Request>()

  private data class Request(val zoom: Int, val bounds: BoundingBox, val thread: String)

  @Test
  fun a_computed_source_renders_features_a_query_can_hit() {
    requireCustomGeometrySourceCallbacks()
    val fixture = BridgeMapFixture.create()
    fixture.use {
      val source = it.attachComputedSource()

      it.awaitComputedFeatures()

      val feature = runBlocking { it.queryCenter().first() }
      assertEquals(FIRST_NAME, feature.properties?.get("name")?.jsonPrimitive?.content)
      assertEquals(setOf("name"), feature.properties?.keys)
      assertEquals(emptyList(), it.errors, "the map should report nothing")

      // The world at zoom 0 is one tile, so this also asserts the bounds are the tile's own.
      val world = requests.first { request -> request.zoom == 0 }
      assertEquals(-180.0, world.bounds.southwest.longitude, TOLERANCE, "west")
      assertEquals(180.0, world.bounds.northeast.longitude, TOLERANCE, "east")
      assertEquals(-MERCATOR_LIMIT, world.bounds.southwest.latitude, TOLERANCE, "south")
      assertEquals(MERCATOR_LIMIT, world.bounds.northeast.latitude, TOLERANCE, "north")

      // The caller's function runs on neither MapLibre's requesting thread nor the map's owner.
      assertTrue(
        requests.all { request -> request.thread == "maplibre-computed-source-$SOURCE_ID" },
        "getFeatures ran somewhere unexpected: ${requests.map { r -> r.thread }.distinct()}",
      )
      // Referenced after the assertions so the source cannot be collected mid-test.
      assertTrue(source.isAttached)
    }
  }

  @Test
  fun invalidating_a_tile_asks_for_its_features_again() {
    requireCustomGeometrySourceCallbacks()
    val fixture = BridgeMapFixture.create()
    fixture.use {
      val source = it.attachComputedSource()
      it.awaitComputedFeatures()

      val answered = requests.size
      source.invalidateTile(zoomLevel = 0, x = 0, y = 0)

      it.pumpUntil("the invalidated tile to be asked for again") { requests.size > answered }
    }
  }

  @Test
  fun invalidating_a_region_asks_for_its_features_again() {
    requireCustomGeometrySourceCallbacks()
    val fixture = BridgeMapFixture.create()
    fixture.use {
      val source = it.attachComputedSource()
      it.awaitComputedFeatures()

      val answered = requests.size
      source.invalidateBounds(
        BoundingBox(southwest = Position(-10.0, -10.0), northeast = Position(10.0, 10.0))
      )

      it.pumpUntil("the invalidated region to be asked for again") { requests.size > answered }
    }
  }

  @Test
  fun setdata_replaces_what_a_tile_was_computed_with() {
    requireCustomGeometrySourceCallbacks()
    val fixture = BridgeMapFixture.create()
    fixture.use {
      val source = it.attachComputedSource()
      it.awaitComputedFeatures()

      source.setData(zoomLevel = 0, x = 0, y = 0, data = cover(WORLD, SECOND_NAME))

      // Waited on rather than checked after a fixed number of frames: the data is tiled on a
      // worker before anything can query it.
      it.pumpUntil("the supplied features to replace the computed ones") {
        it.queryCenter().any { feature ->
          feature.properties?.get("name")?.jsonPrimitive?.content == SECOND_NAME
        }
      }
    }
  }

  private fun BridgeMapFixture.attachComputedSource(): ComputedSource {
    loadStyle(BaseStyle.Empty)
    val style = assertIs<MlnFfiStyle>(this.style, "the style should have reached the callbacks")
    val source =
      ComputedSource(id = SOURCE_ID, options = ComputedSourceOptions()) { bounds, zoom ->
        requests += Request(zoom, bounds, Thread.currentThread().name)
        cover(bounds, FIRST_NAME)
      }
    style.addSource(source)
    style.addLayer(FillLayer(id = "computed-fill", source = source))
    return source
  }

  private suspend fun BridgeMapFixture.queryCenter() =
    session.queryRenderedFeatures(offset = CENTER, layerIds = null, predicate = null)

  private fun BridgeMapFixture.awaitComputedFeatures() {
    pumpUntil("the computed source to request a tile") { requests.isNotEmpty() }
    pumpUntil("the answered computed source tile to be queryable") { queryCenter().isNotEmpty() }
  }

  private fun requireCustomGeometrySourceCallbacks() {
    if (!FfiTestPlatform.runtimeCapabilities.customGeometrySourceCallbacks) {
      FfiTestPlatform.skip(
        "The packaged Android FFI binding does not yet deliver custom-geometry callbacks"
      )
    }
  }

  /** One polygon filling [bounds], so every point of the requested tile is a hit. */
  private fun cover(bounds: BoundingBox, name: String): FeatureCollection<*, *> {
    val west = bounds.southwest.longitude
    val east = bounds.northeast.longitude
    val south = bounds.southwest.latitude
    val north = bounds.northeast.latitude
    val ring =
      listOf(
        Position(west, south),
        Position(east, south),
        Position(east, north),
        Position(west, north),
        Position(west, south),
      )
    return FeatureCollection(
      listOf(
        Feature(
          geometry = Polygon(listOf(ring)),
          properties = buildJsonObject { put("name", name) },
        )
      )
    )
  }

  private companion object {
    const val SOURCE_ID = "computed"
    const val FIRST_NAME = "computed"
    const val SECOND_NAME = "supplied"

    /** The center of [BridgeMapFixture.DEFAULT_EXTENT], in the logical pixels a query takes. */
    val CENTER = DpOffset(256.dp, 256.dp)

    /** Latitude beyond which Web Mercator is undefined, which is where tile row zero ends. */
    const val MERCATOR_LIMIT = 85.0511287798066

    const val TOLERANCE = 1e-9

    val WORLD =
      BoundingBox(
        southwest = Position(-180.0, -MERCATOR_LIMIT),
        northeast = Position(180.0, MERCATOR_LIMIT),
      )
  }
}
