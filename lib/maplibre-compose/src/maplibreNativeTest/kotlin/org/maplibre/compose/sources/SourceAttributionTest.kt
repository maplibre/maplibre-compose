package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.maplibre.compose.layers.TestLayer
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.install
import org.maplibre.compose.style.uninstall
import org.maplibre.compose.testing.MlnFfiMapFixture
import org.maplibre.compose.testing.runMapTest

class SourceAttributionTest {
  @Test
  fun tilejson_attribution_is_published_without_waiting_for_idle() = runMapTest {
    val tileJson = CompletableDeferred<ByteArray>()
    val requested = CompletableDeferred<Unit>()
    val config =
      MapResourceConfig(
        provider =
          MapResourceProvider("fixture") {
            if (it.url == "fixture://source.json") {
              requested.complete(Unit)
              tileJson.await()
            } else awaitCancellation() // Keep tiles pending so the map cannot become idle.
          },
      )
    MlnFfiMapFixture(BridgeMapFixture.create(resourceConfig = config)).use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      fixture.pumpUntil("the first idle", timeout = 5.seconds) {
        MapEvent.Idle in fixture.engineEvents
      }
      val source = VectorTileSource("late", "fixture://source.json")
      assertNotNull(fixture.state.style.sources.add(source))
      val layer = TestLayer("late-layer", "line", source).apply { sourceLayer = "roads" }
      val binding = checkNotNull(fixture.style)
      binding.install(layer)
      fixture.pumpUntil("the TileJSON request", timeout = 5.seconds) { requested.isCompleted }
      assertEquals("", fixture.state.style.sources[source]?.attributionHtml)
      val idleCount = fixture.engineEvents.count { it == MapEvent.Idle }

      tileJson.complete(
        """{"tilejson":"3.0.0","tiles":["fixture://tiles/{z}/{x}/{y}.pbf"],"attribution":"late attribution","minzoom":0,"maxzoom":0}"""
          .encodeToByteArray(),
      )
      fixture.pumpUntil("attribution while tiles are pending", timeout = 5.seconds) {
        fixture.state.style.sources[source]?.attributionHtml == "late attribution"
      }
      assertEquals(idleCount, fixture.engineEvents.count { it == MapEvent.Idle })

      binding.uninstall(layer)
      checkNotNull(fixture.state.style.sources[source]?.asMutable).remove()
      fixture.pumpUntil("the removed source", timeout = 5.seconds) {
        fixture.state.style.sources[source] == null
      }
      assertNull(fixture.state.style.sources[source])
      fixture.loadStyle(
        BaseStyle.Json(
          """{"version":8,"sources":{"replacement":{"type":"geojson","attribution":"replacement attribution","data":{"type":"FeatureCollection","features":[]}}},"layers":[]}""",
        ),
      )
      assertNull(fixture.state.style.sources[source])
      assertEquals(
        "replacement attribution",
        fixture.state.style.sources["replacement"]?.attributionHtml,
      )
    }
  }
}
