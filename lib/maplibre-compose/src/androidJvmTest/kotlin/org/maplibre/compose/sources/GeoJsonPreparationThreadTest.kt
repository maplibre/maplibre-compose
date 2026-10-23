package org.maplibre.compose.sources

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.map.styleSourceIds
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.readMap
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

class GeoJsonPreparationThreadTest {
  @Test
  fun initial_data_serializes_without_blocking_main(): MapTestResult =
    assertWorkerSerialization(initial = true)

  @Test
  fun imperative_data_serializes_without_blocking_main(): MapTestResult =
    assertWorkerSerialization(initial = false)

  private fun assertWorkerSerialization(initial: Boolean): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val binding = fixture.style as MlnFfiStyleBinding
      val main = Thread.currentThread()
      val entered = CompletableDeferred<Unit>()
      val release = CountDownLatch(1)
      val feature = Feature(Point(Position(0.0, 0.0)), JsonObject(emptyMap()))
      val features =
        object : AbstractList<Feature<Point, JsonObject>>() {
          override val size = 1

          override fun get(index: Int): Feature<Point, JsonObject> {
            assertNotSame(main, Thread.currentThread(), "serialization ran on main")
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)) { "serialization was not released" }
            return feature
          }
        }
      val data = GeoJsonData.Features(FeatureCollection(features))
      try {
        val handle =
          assertIs<GeoJsonSourceHandle>(
            fixture.state.style.sources.add(
              GeoJsonSource(
                "points",
                if (initial) data else GeoJsonData.JsonString(Empty),
              ) {
                synchronousTiling = true
              },
            ),
          )
        if (!initial) {
          binding.awaitGeoJsonUpdates()
          handle.asMutable!!.setData(data)
        }
        withTimeout(5.seconds) { entered.await() }
        // Preparation is still blocked, but main and the map still answer a query.
        assertTrue("points" in binding.readMap { it.styleSourceIds() })
      } finally {
        release.countDown()
      }
      binding.awaitGeoJsonUpdates()
      assertEquals(emptyList(), fixture.errors)
    }
  }

  private companion object {
    const val Empty = """{"type":"FeatureCollection","features":[]}"""
  }
}
