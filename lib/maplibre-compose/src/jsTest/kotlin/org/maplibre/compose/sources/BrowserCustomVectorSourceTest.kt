package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class BrowserCustomVectorSourceTest {

  @Test
  fun empty_mvt_data_completes_as_an_empty_tile(): MapTestResult = runMapTest {
    val requests = RecordingList<TileCoordinate>()
    val release = CompletableDeferred<Unit>()
    createMapFixture().use { fixture ->
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
      style.addSource(source)
      style.addLayer(layer)

      fixture.pumpUntil("the empty custom MVT tile to be requested") { requests.isNotEmpty() }
      fun isSourceLoaded(): Boolean =
        source.binding?.withMap { map -> map.isSourceLoaded(source.id) } == true

      assertFalse(isSourceLoaded())
      release.complete(Unit)
      fixture.pumpUntil("the empty custom MVT tile to finish loading") { isSourceLoaded() }

      assertTrue(source.querySourceFeatures(setOf("points")).isEmpty())
    }
  }

  @Test
  fun simultaneous_sources_use_unique_protocol_urls(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(fixture.style)
      val first = CustomVectorSource("first", CustomVectorSourceOptions()) { byteArrayOf() }
      val second = CustomVectorSource("second", CustomVectorSourceOptions()) { byteArrayOf() }

      style.addSource(first)
      style.addSource(second)

      val firstUrl = first.toJson()["tiles"]?.jsonArray?.single()?.jsonPrimitive?.content
      val secondUrl = second.toJson()["tiles"]?.jsonArray?.single()?.jsonPrimitive?.content
      assertNotEquals(firstUrl, secondUrl)
    }
  }

  @Test
  fun provider_failure_rejects_the_protocol_request(): MapTestResult = runMapTest {
    var requested = false
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertNotNull(fixture.style)
      val source =
        CustomVectorSource("failing", CustomVectorSourceOptions(minZoom = 0, maxZoom = 0)) {
          requested = true
          error("fixture protocol failure")
        }
      val layer = CircleLayer("failing-points", source)
      layer.sourceLayer = "points"
      style.addSource(source)
      style.addLayer(layer)

      fixture.pumpUntil("the rejected protocol request to reach MapLibre GL JS") {
        requested && source.binding?.lastReportedError != null
      }

      assertTrue(requested)
      assertNotNull(source.binding?.lastReportedError)
    }
  }
}
