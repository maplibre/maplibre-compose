package org.maplibre.compose.sources

import js.objects.unsafeJso
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.gljs.JumpToOptions
import org.maplibre.compose.gljs.QuerySourceFeatureOptions
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.GlJsStyleBinding
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

class BrowserCustomGeometrySourceTest {

  @Test
  fun empty_geometry_completes_as_an_empty_tile(): MapTestResult = runMapTest {
    val requests = RecordingList<TileCoordinate>()
    val release = CompletableDeferred<Unit>()
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertIs<GlJsStyleBinding>(fixture.style)
      val source =
        CustomGeometrySource("empty", CustomGeometrySourceOptions(minZoom = 0, maxZoom = 0)) {
          requests += it
          release.await()
          noFeatures()
        }
      val layer = TestLayer("empty-fill", "fill", source)
      style.install(source)
      style.install(layer)

      fixture.pumpUntil("the empty custom geometry tile to be requested") { requests.isNotEmpty() }
      fun isSourceLoaded(): Boolean = style.withMap { map -> map.isSourceLoaded(source.id) } == true

      assertFalse(isSourceLoaded())
      release.complete(Unit)
      fixture.pumpUntil("the empty custom geometry tile to finish loading") { isSourceLoaded() }
    }
  }

  @Test
  fun a_failed_request_completes_as_empty_and_allows_pending_invalidation(): MapTestResult =
    runMapTest {
      var requests = 0
      val fail = CompletableDeferred<Unit>()
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        val style = assertIs<GlJsStyleBinding>(fixture.style)
        val source =
          CustomGeometrySource("failing", CustomGeometrySourceOptions(minZoom = 0, maxZoom = 0)) {
            requests++
            if (requests == 1) {
              fail.await()
              error("fixture protocol failure")
            }
            noFeatures()
          }
        val layer = TestLayer("failing-fill", "fill", source)
        val handle = assertIs<CustomGeometrySourceHandle>(fixture.state.style.sources.add(source))
        style.install(layer)
        fixture.pumpUntil("the provider to start") { requests == 1 }
        handle.invalidateTile(TileCoordinate(0, 0, 0))
        fail.complete(Unit)

        fixture.pumpUntil("the invalidated tile to load after the failure") {
          requests > 1 && style.withMap { it.isSourceLoaded(source.id) } == true
        }
        assertNull(style.lastReportedError)
      }
    }

  @Test
  fun invalidation_refreshes_both_loaded_and_in_flight_tiles(): MapTestResult = runMapTest {
    val requests = mutableMapOf<TileCoordinate, Int>()
    val release = CompletableDeferred<Unit>()
    var heldTile: TileCoordinate? = null
    var revision = "old"
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertIs<GlJsStyleBinding>(fixture.style)
      style.withMap { it.jumpTo(unsafeJso<JumpToOptions> { zoom = 1.0 }) }
      val source =
        CustomGeometrySource("in-flight", CustomGeometrySourceOptions(minZoom = 1, maxZoom = 1)) {
          tile ->
          val name = revision
          requests[tile] = (requests[tile] ?: 0) + 1
          if (heldTile == null) heldTile = tile
          if (tile == heldTile && name == "old") release.await()
          FeatureCollection(
            listOf(
              Feature(
                geometry =
                  Point(
                    Position(
                      (tile.bounds.southwest.longitude + tile.bounds.northeast.longitude) / 2,
                      (tile.bounds.southwest.latitude + tile.bounds.northeast.latitude) / 2,
                    )
                  ),
                properties = buildJsonObject { put("name", name) },
              )
            )
          )
        }
      val handle = assertIs<CustomGeometrySourceHandle>(fixture.state.style.sources.add(source))
      style.install(TestLayer("in-flight-fill", "fill", source))
      fun names(): List<String> =
        style
          .withMap { map ->
            map
              .querySourceFeatures(
                source.id,
                unsafeJso<QuerySourceFeatureOptions> {
                  sourceLayer = source.id
                },
              )
              .map { it.asDynamic().properties.name as String }
          }
          .orEmpty()

      fixture.pumpUntil("some tiles to load while another remains suspended") {
        requests.size == 4 && names().isNotEmpty()
      }
      revision = "new"
      handle.invalidateTile(assertNotNull(heldTile))
      handle.invalidateBounds(TileCoordinate(0, 0, 0).bounds)
      fixture.pump(frames = 30)
      release.complete(Unit)

      fixture.pumpUntil("every visible tile to contain the new features") {
        requests.values.all { it >= 2 } && names().size >= 4 && names().all { it == "new" }
      }
      assertEquals(emptyList(), fixture.errors)
    }
  }

  private fun noFeatures(): FeatureCollection<*, *> =
    FeatureCollection<Geometry, JsonObject?>(emptyList())
}
