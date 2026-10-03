@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.maplibre.compose.benchmark

import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
  fun fractionalPeriodsNeverSubmitTheSameSlotTwice() = runTest {
    val times = mutableListOf<Long>()
    val clock = BenchmarkWorkload(12000, { error("Not frame scheduled") }, testScheduler.timeSource)
    clock.scheduled(30.0) {
      times += testScheduler.currentTime
      clock.submitted()
    }
    assertEquals(360, clock.report().operations)
    assertEquals(times.distinct(), times)
    assertTrue(times.zipWithNext().all { (a, b) -> b - a in 33L..34L })
  }

  @Test
  fun largeStylesPartitionThePointsAndReturningMapsInstallContentAfterLoading() = runTest {
    suspend fun fixture(scenario: BenchmarkScenario, implementation: BenchmarkImplementation) =
      loadBenchmarkFixture(
        BenchmarkConfig(scenario = scenario, implementation = implementation, layers = 256),
        read = { """{"type":"FeatureCollection","features":[]}""" },
        uri = { error("Must use prepared point data") },
      )
    for (implementation in
      listOf(
        BenchmarkImplementation.Declarative,
        BenchmarkImplementation.ClassicAndroid,
        BenchmarkImplementation.ClassicIos,
      )) {
      val returning = fixture(BenchmarkScenario.MapReturn, implementation)
      assertTrue(returning.partitioned)
      assertTrue(returning.usesImages)
      val style = Json.parseToJsonElement(returning.baseStyles[0]).jsonObject
      assertTrue(style.getValue("sources").jsonObject.isEmpty())
      assertEquals(1, style.getValue("layers").jsonArray.size)
    }
    val sparse = fixture(BenchmarkScenario.SparsePaint, BenchmarkImplementation.ClassicAndroid)
    val layers =
      Json.parseToJsonElement(sparse.baseStyles[0]).jsonObject.getValue("layers").jsonArray.drop(1)
    assertEquals(256, layers.size)
    layers.forEachIndexed { index, layer ->
      assertEquals(
        Json.parseToJsonElement(benchmarkLayerFilter(index, 256)),
        layer.jsonObject["filter"],
      )
      assertEquals(
        Json.parseToJsonElement(BenchmarkRadiusExpression),
        layer.jsonObject.getValue("paint").jsonObject["circle-radius"],
      )
    }
  }

  @Test
  fun imageCycleTimesRemovalAndWaitsForCompletion() = runTest {
    val events = mutableListOf<String>()
    val config =
      BenchmarkConfig(scenario = BenchmarkScenario.ImageCycle, durationMs = 3000, rateHz = 1.0)
    val driver =
      TimingDriver(
        config,
        action = { event ->
          events += event
          testScheduler.advanceTimeBy(if (event == "remove") 20 else 30)
        },
        settle = {
          events += "settled"
          delay(50)
        },
      )
    val clock = BenchmarkWorkload(config.durationMs, driver.nextFrame, testScheduler.timeSource)
    driver.run(clock)
    assertEquals(List(3) { listOf("remove", "register", "settled") }.flatten(), events)
    assertEquals(List(3) { 50.0 }, clock.report().submissionMs)
    assertEquals(List(3) { 100.0 }, clock.report().completionMs)
    assertEquals("map-settled", clock.report().completionSignal)
  }

  @Test
  fun returningMapsCloseOnceWhileAttachedThenAwaitCleanup() = runTest {
    val config =
      BenchmarkConfig(
        scenario = BenchmarkScenario.MapReturn,
        implementation = BenchmarkImplementation.Declarative,
        durationMs = 3000,
        rateHz = 1.0,
      )
    var mounted = false
    var created = 0
    var closed = 0
    var completed = 0
    val nextFrame: suspend () -> Long = {
      delay(16)
      testScheduler.currentTime * 1_000_000
    }
    val failure =
      runMapReturnBenchmark(
        config,
        nextFrame,
        mount = {
          assertFalse(mounted)
          mounted = true
          created++
          TimingDriver(
            config,
            nextFrame,
            action = {
              assertEquals("close", it)
              assertTrue(mounted)
              closed++
            },
            awaitClose = {
              assertFalse(mounted)
              assertEquals(created, closed)
              completed++
            },
          )
        },
        unmount = {
          assertEquals(created, closed)
          mounted = false
        },
        cover = {},
        host = BenchmarkHost(cpu = {}, collectGarbage = {}),
        timeSource = testScheduler.timeSource,
      )
    assertNull(failure)
    assertTrue(created > 1) // Priming is followed by measured maps.
    assertEquals(created, closed)
    assertEquals(created, completed)
    assertFalse(mounted)
  }

  @Test
  fun closeReturnAndCompletionAreRecordedSeparately() = runTest {
    val clock = BenchmarkWorkload(3000, { error("No frame needed") }, testScheduler.timeSource)
    val events = mutableListOf<String>()
    clock.submitted()
    clock.close(
      close = {
        events += "close"
        testScheduler.advanceTimeBy(7)
      },
      awaitClosed = {
        events += "await"
        delay(31)
      },
    )
    assertEquals(listOf("close", "await"), events)
    assertEquals(listOf(7.0), clock.report().closeMs)
    assertEquals(listOf(38.0), clock.report().closeCompletionMs)
    assertEquals(1, clock.report().operations)
  }

  @Test
  fun publicationAndMetadataConsumersUseTheSamePartitionedBaseStyle() = runTest {
    var control: List<String>? = null
    for (scenario in
      listOf(
        BenchmarkScenario.Style,
        BenchmarkScenario.StyleOverlay,
        BenchmarkScenario.OverlayUpdate,
      )) {
      val fixture =
        loadBenchmarkFixture(
          BenchmarkConfig(
            scenario = scenario,
            implementation = BenchmarkImplementation.Declarative,
            layers = 600,
          ),
          read = { """{"type":"FeatureCollection","features":[]}""" },
          uri = { error("No external resources") },
        )
      if (control == null) control = fixture.baseStyles
      else assertEquals(control, fixture.baseStyles)
      val style = Json.parseToJsonElement(fixture.baseStyles.first()).jsonObject
      assertEquals(setOf("data"), style.getValue("sources").jsonObject.keys)
      val layers = style.getValue("layers").jsonArray
      assertEquals(601, layers.size)
      assertEquals(
        Json.parseToJsonElement(benchmarkLayerFilter(0, 600)),
        layers[1].jsonObject["filter"],
      )
      assertEquals("workload-599", layers.last().jsonObject.getValue("id").jsonPrimitive.content)
    }
    val route =
      loadBenchmarkFixture(
        BenchmarkConfig(scenario = BenchmarkScenario.Style, scene = BenchmarkScene.Route),
        read = { """{"type":"FeatureCollection","features":[]}""" },
        uri = { error("No external resources") },
      )
    assertFalse(route.partitioned)
    val routeLayer =
      Json.parseToJsonElement(route.baseStyles.first())
        .jsonObject
        .getValue("layers")
        .jsonArray[1]
        .jsonObject
    assertEquals("line", routeLayer.getValue("type").jsonPrimitive.content)
    assertFalse("circle-radius" in routeLayer.getValue("paint").jsonObject)
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
    assertEquals(listOf(1000.0, 1000.0, 1000.0), clock.report().frameIntervalMs)
  }

  @Test
  fun engineAnimationDoesNotDependOnUiCallbackFrequency() = runTest {
    val config = BenchmarkConfig(scenario = BenchmarkScenario.Animation, durationMs = 3000)
    val animations = mutableListOf<Long>()
    val driver =
      TimingDriver(
        config,
        nextFrame = {
          delay(1000)
          testScheduler.currentTime * 1_000_000
        },
        animation = { duration ->
          animations += duration
          delay(duration)
        },
      )
    val clock = BenchmarkWorkload(config.durationMs, driver.nextFrame, testScheduler.timeSource)
    driver.run(clock)
    assertEquals(listOf(3000L), animations)
    assertEquals(3, clock.report().frameIntervalMs.size)
    assertEquals(3000.0, clock.report().durationMs)
  }

  @Test
  fun callbackIntervalsIncludeInitialAndDeadlineCrossingWaits() = runTest {
    for (waits in listOf(listOf(2000L, 500L, 500L), listOf(500L, 500L, 5000L))) {
      var frame = 0
      val clock =
        BenchmarkWorkload(
          3000,
          {
            delay(waits[frame++])
            testScheduler.currentTime * 1_000_000
          },
          testScheduler.timeSource,
        )
      clock.frames {}
      assertEquals(2000.0, clock.report().frameIntervalMs.max())
      assertEquals(3000.0, clock.report().frameIntervalMs.sum())
    }
  }

  @Test
  fun delayedCallbacksKeepTheConfiguredFrameWindow() = runTest {
    var collectedDuration: Long? = null
    val frames =
      object : BenchmarkUiFrames {
        override fun start(durationMillis: Long?) {
          collectedDuration = durationMillis
        }

        override suspend fun stop() {}
      }
    val failure =
      measured(
        BenchmarkHost(cpu = {}, collectGarbage = {}, uiFrames = frames),
        frameDurationMillis = 3000,
        measure = { _, start ->
          start()
          val clock =
            BenchmarkWorkload(
              3000,
              {
                delay(6000)
                testScheduler.currentTime * 1_000_000
              },
              testScheduler.timeSource,
            )
          clock.frames {}
          clock.report()
        },
        cleanup = {},
      )
    assertNull(failure)
    assertEquals(6000L, testScheduler.currentTime)
    assertEquals(3000L, collectedDuration)
  }

  @Test
  fun animationCompletionDoesNotExtendTheMeasurement() = runTest {
    val config = BenchmarkConfig(scenario = BenchmarkScenario.Animation, durationMs = 3000)
    var stopped = false
    val driver =
      TimingDriver(
        config,
        nextFrame = {
          delay(1000)
          testScheduler.currentTime * 1_000_000
        },
        animation = { duration ->
          try {
            delay(duration * 3)
          } finally {
            stopped = true
          }
        },
      )
    val clock = BenchmarkWorkload(config.durationMs, driver.nextFrame, testScheduler.timeSource)
    driver.run(clock)
    assertTrue(stopped)
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

/** Only the SDK boundaries used by timing tests; the real workload controls order and clocks. */
private class TimingDriver(
  config: BenchmarkConfig,
  nextFrame: suspend () -> Long = { error("No frame expected") },
  private val action: (String) -> Unit = {},
  private val settle: suspend () -> Unit = {},
  private val awaitClose: suspend () -> Unit = {},
  private val animation: suspend (Long) -> Unit = { error("Unused") },
) : BenchmarkDriver(PreparedBenchmarkFixture(config, emptyList(), emptyList()), nextFrame) {
  override suspend fun prepare(scope: CoroutineScope) = StartupReport(0.0, 0.0)

  override fun registerImages() = action("register")

  override fun removeImages() = action("remove")

  override suspend fun settled(block: suspend () -> Unit) {
    block()
    settle()
  }

  override fun close() = action("close")

  override suspend fun awaitClosed() = awaitClose()

  override fun viewport() = listOf(400.0, 800.0, 1.0)

  override fun recordFrames(recorder: BenchmarkFrameRecorder?) {}

  override fun camera(value: BenchmarkCamera): Unit = error("Unused")

  override suspend fun animate(value: BenchmarkCamera, durationMs: Long) = animation(durationMs)

  override fun style(index: Int): Deferred<Unit> = error("Unused")

  override fun image(index: Int): Unit = error("Unused")

  override fun layers(show: Boolean): Unit = error("Unused")

  override fun paint(index: Int): Unit = error("Unused")

  override fun sparsePaint(index: Int): Unit = error("Unused")

  override fun visible(show: Boolean): Unit = error("Unused")

  override fun source(index: Int): Unit = error("Unused")

  override fun height(fraction: Double): Unit = error("Unused")

  override fun padding(bottom: Double): Unit = error("Unused")

  override suspend fun renderedRevisions(): List<Int> = error("Unused")
}
