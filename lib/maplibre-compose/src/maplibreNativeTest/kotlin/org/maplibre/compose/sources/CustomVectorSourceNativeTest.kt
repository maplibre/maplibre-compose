package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.install
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.MlnFfiMapFixture
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class CustomVectorSourceNativeTest {

  @Test
  fun empty_mvt_data_completes_as_an_empty_tile(): MapTestResult = runMapTest {
    val requests = RecordingList<TileCoordinate>()
    val release = CompletableDeferred<Unit>()
    val fixture = createMapFixture() as MlnFfiMapFixture
    fixture.use {
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(fixture.style)
      val source =
        CustomVectorSource("empty", CustomVectorSourceOptions(minZoom = 0, maxZoom = 0)) { tile ->
          requests += tile
          release.await()
          byteArrayOf()
        }
      val layer = CircleLayer("empty-points", source)
      layer.sourceLayer = "points"
      style.install(source)
      style.install(layer)

      fixture.pumpUntil("the empty custom MVT tile to be requested") { requests.isNotEmpty() }
      fun isMapFullyLoaded(): Boolean =
        fixture.bridge.session.readMap { map -> map.isFullyLoaded } == true

      assertFalse(isMapFullyLoaded())
      release.complete(Unit)
      fixture.pumpUntil("the empty custom MVT tile to finish loading") { isMapFullyLoaded() }

      fixture.state.desiredStyleRevision =
        DesiredStyleRevision(listOf(source.definition()), emptyList(), emptyList())
      val handle = assertIs<CustomVectorSourceHandle>(fixture.state.style.source("empty"))
      assertTrue(handle.querySourceFeatures(setOf("points")).isEmpty())
      val answered = requests.size

      handle.invalidateTile(TileCoordinate(zoomLevel = 0, x = 0, y = 0))

      fixture.pumpUntil("the invalidated custom MVT tile to be requested again") {
        requests.size > answered
      }
    }
  }
}
