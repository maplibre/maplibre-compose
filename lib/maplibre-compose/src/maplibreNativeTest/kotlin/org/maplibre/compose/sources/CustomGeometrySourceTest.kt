package org.maplibre.compose.sources

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.RecordingList
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

/** Drives custom geometry callbacks through a real MapLibre map. */
class CustomGeometrySourceTest {
  private val requests = RecordingList<TileCoordinate>()

  @Volatile private var featureName = FIRST_NAME

  @Test
  fun a_custom_geometry_source_renders_features_a_query_can_hit() {
    requireCustomGeometrySourceCallbacks()
    BridgeMapFixture.create().use { fixture ->
      val source = fixture.attachCustomGeometrySource()

      fixture.awaitCustomFeatures()

      val feature = runBlocking { fixture.queryCenter().first() }
      assertEquals(FIRST_NAME, feature.properties?.get("name")?.jsonPrimitive?.content)
      assertEquals(setOf("name"), feature.properties?.keys)
      assertEquals(emptyList(), fixture.errors, "the map should report nothing")
      assertEquals(TileCoordinate(0, 0, 0).bounds, requests.first { it.zoomLevel == 0 }.bounds)
    }
  }

  @Test
  fun replacing_the_style_cancels_an_outstanding_provider_call() {
    requireCustomGeometrySourceCallbacks()
    val state = CancellationState()
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}"""))
      val style = assertIs<MlnFfiStyleBinding>(fixture.style)
      val source =
        CustomGeometrySource(SOURCE_ID, CustomGeometrySourceOptions()) {
          state.started = true
          try {
            awaitCancellation()
          } finally {
            state.cancelled = true
          }
        }
      style.install(source)
      style.install(FillLayer(id = "custom-fill", source = source))
      fixture.pumpUntil("the provider to start") { state.started }

      fixture.loadStyle(BaseStyle.Empty)

      fixture.pumpUntil("the provider to be cancelled") { state.cancelled }
    }
  }

  @Test
  fun provider_failure_completes_as_an_empty_tile() {
    requireCustomGeometrySourceCallbacks()
    val requested = CompletableDeferred<Unit>()
    val fail = CompletableDeferred<Unit>()
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertIs<MlnFfiStyleBinding>(fixture.style)
      val source =
        CustomGeometrySource(SOURCE_ID, CustomGeometrySourceOptions()) {
          requested.complete(Unit)
          fail.await()
          error("fixture provider failure")
        }
      style.install(source)
      style.install(FillLayer(id = "custom-fill", source = source))

      fixture.pumpUntil("the source to request a tile") { requested.isCompleted }
      assertFalse(
        fixture.session.readMap { map -> map.isFullyLoaded } ?: true,
        "the pending provider must keep the map from finishing its load",
      )
      fail.complete(Unit)
      fixture.pumpUntil("the failed tile request to complete", 5.seconds) {
        fixture.session.readMap { map -> map.isFullyLoaded } == true
      }

      assertTrue(runBlocking { fixture.queryCenter() }.isEmpty())
    }
  }

  private fun BridgeMapFixture.attachCustomGeometrySource(): CustomGeometrySource {
    loadStyle(BaseStyle.Empty)
    val style = assertIs<MlnFfiStyleBinding>(this.style)
    val source =
      CustomGeometrySource(id = SOURCE_ID, options = CustomGeometrySourceOptions()) { tile ->
        requests += tile
        cover(tile.bounds, featureName)
      }
    style.install(source)
    style.install(FillLayer(id = "custom-fill", source = source))
    return source
  }

  private suspend fun BridgeMapFixture.queryCenter() =
    session.queryRenderedFeatures(offset = CENTER, layerIds = null, predicate = null)

  private fun BridgeMapFixture.awaitCustomFeatures() {
    pumpUntil("the custom geometry source to request a tile") { requests.isNotEmpty() }
    pumpUntil("the answered custom geometry tile to be queryable") { queryCenter().isNotEmpty() }
  }

  private fun requireCustomGeometrySourceCallbacks() {
    if (!FfiTestPlatform.runtimeCapabilities.customGeometrySourceCallbacks) {
      FfiTestPlatform.skip(
        "The packaged Android FFI binding does not deliver custom geometry callbacks"
      )
    }
  }

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

  private class CancellationState {
    @Volatile var started = false
    @Volatile var cancelled = false
  }

  private companion object {
    const val SOURCE_ID = "custom-geometry"
    const val FIRST_NAME = "first"
    const val SECOND_NAME = "second"
    val CENTER = DpOffset(256.dp, 256.dp)
  }
}
