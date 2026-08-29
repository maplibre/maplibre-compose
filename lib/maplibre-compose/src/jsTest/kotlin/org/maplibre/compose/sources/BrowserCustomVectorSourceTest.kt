package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.maplibre.compose.gljs.SourceHandle
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.GlJsStyleBinding
import org.maplibre.compose.style.install
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
      val style = assertIs<GlJsStyleBinding>(fixture.style)
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
      fun isSourceLoaded(): Boolean = style.withMap { map -> map.isSourceLoaded(source.id) } == true

      assertFalse(isSourceLoaded())
      release.complete(Unit)
      fixture.pumpUntil("the empty custom MVT tile to finish loading") { isSourceLoaded() }
    }
  }

  @Test
  fun simultaneous_sources_use_unique_protocol_urls(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertIs<GlJsStyleBinding>(fixture.style)
      val first = CustomVectorSource("first", CustomVectorSourceOptions()) { byteArrayOf() }
      val second = CustomVectorSource("second", CustomVectorSourceOptions()) { byteArrayOf() }

      style.install(first)
      style.install(second)

      val firstUrl = assertNotNull(first.liveTileUrlTemplate(style))
      val secondUrl = assertNotNull(second.liveTileUrlTemplate(style))
      assertNotEquals(firstUrl, secondUrl)
    }
  }

  /** The tile URL template MapLibre GL JS holds for this live source. */
  private fun CustomVectorSource.liveTileUrlTemplate(style: GlJsStyleBinding): String? =
    style
      .withMap { map -> map.getSource<SourceHandle>(id)?.asDynamic()?.serialize()?.tiles }
      ?.unsafeCast<Array<String>>()
      ?.single()

  @Test
  fun provider_failure_rejects_the_protocol_request(): MapTestResult = runMapTest {
    var requested = false
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = assertIs<GlJsStyleBinding>(fixture.style)
      val source =
        CustomVectorSource("failing", CustomVectorSourceOptions(minZoom = 0, maxZoom = 0)) {
          requested = true
          error("fixture protocol failure")
        }
      val layer = CircleLayer("failing-points", source)
      layer.sourceLayer = "points"
      style.install(source)
      style.install(layer)

      fixture.pumpUntil("the rejected protocol request to reach MapLibre GL JS") {
        requested && style.lastReportedError != null
      }

      assertTrue(requested)
      assertNotNull(style.lastReportedError)
    }
  }
}
