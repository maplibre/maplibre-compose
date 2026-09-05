package org.maplibre.compose.sources

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.StyleHandleException
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

  @Test
  fun synchronous_initial_data_serializes_on_the_owner_before_returning(): MapTestResult =
    assertOwnerSerialization(initial = true)

  @Test
  fun synchronous_imperative_data_serializes_on_the_owner_before_returning(): MapTestResult =
    assertOwnerSerialization(initial = false)

  @Test
  fun synchronous_serialization_errors_are_reported_as_handle_failures(): MapTestResult =
    runMapTest {
      createMapFixture().use { fixture ->
        fixture.loadStyle(BaseStyle.Empty)
        val failure = IllegalArgumentException("Could not serialize feature")
        val features =
          object : AbstractList<Feature<Point, JsonObject>>() {
            override val size = 1

            override fun get(index: Int): Feature<Point, JsonObject> = throw failure
          }
        val data = GeoJsonData.Features(FeatureCollection(features))
        val options = GeoJsonOptions(synchronousUpdate = true)

        val addFailure =
          assertFailsWith<StyleHandleException> {
            fixture.state.style.sources.add(GeoJsonSource("points", data, options))
          }
        assertSame(failure, addFailure.cause?.cause)

        val handle =
          assertIs<GeoJsonSourceHandle>(
            fixture.state.style.sources.add(
              GeoJsonSource("points", GeoJsonData.JsonString(EMPTY), options)
            )
          )
        val updateFailure = assertFailsWith<StyleHandleException> { handle.setData(data) }
        assertSame(failure, updateFailure.cause?.cause)
        (fixture.style as MlnFfiStyleBinding).awaitGeoJsonUpdates()
        fixture.settle()
        assertEquals(
          emptyList(),
          fixture.engineEvents.filterIsInstance<MapEvent.SourceDataFailed>(),
        )
      }
    }

  private fun assertOwnerSerialization(initial: Boolean): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val binding = fixture.style as MlnFfiStyleBinding
      val owner = checkNotNull(binding.readMap { Thread.currentThread() })
      val serialized = CountDownLatch(1)
      val feature = Feature(Point(Position(0.0, 0.0)), JsonObject(emptyMap()))
      val features =
        object : AbstractList<Feature<Point, JsonObject>>() {
          override val size = 1

          override fun get(index: Int): Feature<Point, JsonObject> {
            assertSame(owner, Thread.currentThread(), "serialization did not run on the owner")
            serialized.countDown()
            return feature
          }
        }
      val data = GeoJsonData.Features(FeatureCollection(features))
      val handle =
        assertIs<GeoJsonSourceHandle>(
          fixture.state.style.sources.add(
            GeoJsonSource(
              "points",
              if (initial) data else GeoJsonData.JsonString(EMPTY),
              GeoJsonOptions(synchronousUpdate = true),
            )
          )
        )
      if (!initial) handle.setData(data)

      assertEquals(0L, serialized.count, "submission returned before serialization")
      assertEquals(emptyList(), fixture.errors)
    }
  }

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
