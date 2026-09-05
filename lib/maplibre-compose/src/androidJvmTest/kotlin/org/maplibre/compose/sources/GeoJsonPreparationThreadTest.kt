package org.maplibre.compose.sources

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

class GeoJsonPreparationThreadTest {
  @Test
  fun initial_data_serializes_without_blocking_the_caller_or_owner(): MapTestResult =
    assertWorkerSerialization(initial = true)

  @Test
  fun imperative_data_serializes_without_blocking_the_caller_or_owner(): MapTestResult =
    assertWorkerSerialization(initial = false)

  private fun assertWorkerSerialization(initial: Boolean): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val binding = fixture.style as MlnFfiStyleBinding
      val caller = Thread.currentThread()
      val owner = checkNotNull(binding.readMap { Thread.currentThread() })
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val feature = Feature(Point(Position(0.0, 0.0)), JsonObject(emptyMap()))
      val features =
        object : AbstractList<Feature<Point, JsonObject>>() {
          override val size = 1

          override fun get(index: Int): Feature<Point, JsonObject> {
            assertNotSame(caller, Thread.currentThread(), "serialization ran on the caller")
            assertNotSame(owner, Thread.currentThread(), "serialization ran on the owner")
            entered.countDown()
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
                if (initial) data else GeoJsonData.JsonString(EMPTY),
                GeoJsonOptions(),
              )
            )
          )
        if (!initial) {
          binding.awaitGeoJsonUpdates()
          handle.setData(data)
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "worker did not begin serialization")
        // Preparation is still blocked, but an owner-thread round trip must finish.
        assertTrue(binding.readMap { it.styleSourceIds().contains("points") } == true)
      } finally {
        release.countDown()
      }
      binding.awaitGeoJsonUpdates()
      assertEquals(emptyList(), fixture.errors)
    }
  }

  private companion object {
    const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
  }
}
