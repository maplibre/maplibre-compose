@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.maplibre.compose.benchmark

import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class BenchmarkCoreTest {
  @Test
  fun missedMutationSlotsDoNotCreateCatchUpBursts() = runTest {
    val times = mutableListOf<Long>()
    val clock =
      BenchmarkWorkload(
        3000,
        { error("Scheduled work must not request frames") },
        testScheduler.timeSource,
      )
    clock.scheduled(4.0) {
      times += testScheduler.currentTime
      delay(1100)
      clock.submitted()
    }
    assertEquals(listOf(0L, 1250L, 2500L), times)
    assertEquals(3, clock.report().operations)
  }

  @Test
  fun frameWorkStopsAtTheMeasurementDeadline() = runTest {
    val progress = mutableListOf<Double>()
    val clock =
      BenchmarkWorkload(
        3000,
        {
          delay(1000)
          testScheduler.currentTime * 1_000_000
        },
        testScheduler.timeSource,
      )
    clock.frames { progress += it }
    assertEquals(listOf(1.0 / 3.0), progress)
    assertEquals(1, clock.report().operations)
    assertEquals(3000.0, clock.report().durationMs)
  }

  @Test
  fun classicHostsUseTheSameInlineFixtureAndResourceUrls() = runTest {
    suspend fun fixture(implementation: BenchmarkImplementation) =
      loadBenchmarkFixture(
        BenchmarkConfig(
          scenario = BenchmarkScenario.Paint,
          implementation = implementation,
          layers = 2,
        ),
        read = { """{"type":"FeatureCollection","features":[]}""" },
        uri = { error("Point styles must be self-contained") },
      )
    val android = fixture(BenchmarkImplementation.ClassicAndroid)
    val ios = fixture(BenchmarkImplementation.ClassicIos)
    assertEquals(android.data, ios.data)
    assertEquals(android.baseStyles, ios.baseStyles)
    val style = Json.parseToJsonElement(ios.baseStyles[0]).jsonObject
    assertEquals(3, style.getValue("layers").jsonArray.size)
    assertEquals(
      "geojson",
      style
        .getValue("sources")
        .jsonObject
        .getValue("data")
        .jsonObject
        .getValue("type")
        .jsonPrimitive
        .content,
    )
    val basemap =
      loadBenchmarkFixture(
        BenchmarkConfig(scene = BenchmarkScene.Basemap),
        read = { error("Basemap uses packaged tiles") },
        uri = { "file:///fixtures/$it" },
      )
    val map = Json.parseToJsonElement(basemap.baseStyles[0]).jsonObject
    assertEquals(
      "file:///fixtures/basemap/glyphs/{fontstack}/{range}.pbf",
      map.getValue("glyphs").jsonPrimitive.content,
    )
    assertEquals(
      "file:///fixtures/basemap/tiles/{z}/{x}/{y}.pbf",
      map
        .getValue("sources")
        .jsonObject
        .getValue("basemap")
        .jsonObject
        .getValue("tiles")
        .jsonArray
        .single()
        .jsonPrimitive
        .content,
    )
  }
}
