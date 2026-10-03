package org.maplibre.compose.benchmark

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString

/**
 * Map SDK operations for one host. The base class owns the workload order, the reset between the
 * warm-up and measured passes, and the completion semantics, so every host measures the same thing.
 */
abstract class BenchmarkDriver(
  val fixture: PreparedBenchmarkFixture,
  val nextFrame: suspend () -> Long,
  /** When the host started creating the map, for [StartupReport]; defaults to construction. */
  private val created: TimeMark = TimeSource.Monotonic.markNow(),
) {
  val config = fixture.config

  protected fun sinceCreation(): Double = created.elapsedNow().inWholeNanoseconds / 1e6

  /**
   * Loads the style, waits for the map to settle, and reports when the style loaded and when the
   * first complete frame rendered. Helpers that outlive this call belong in [scope].
   */
  abstract suspend fun prepare(scope: CoroutineScope): StartupReport

  abstract fun camera(value: BenchmarkCamera)

  abstract suspend fun animate(value: BenchmarkCamera, durationMs: Long)

  abstract fun style(index: Int): Deferred<Unit>

  abstract fun image(index: Int)

  open suspend fun prepareImage(index: Int): Unit = error("${config.scenario.id} is Compose-only")

  open fun overlay(show: Boolean): Unit = error("${config.scenario.id} is Compose-only")

  open suspend fun overlayVisible(): Boolean = error("${config.scenario.id} is Compose-only")

  abstract fun layers(show: Boolean)

  abstract fun paint(index: Int)

  /** Recolors only the first data layer; the rest of a large style stays untouched. */
  abstract fun sparsePaint(index: Int)

  /** Registers `imageCount` ids using reusable prepared images. */
  abstract fun registerImages()

  abstract fun removeImages()

  abstract fun visible(show: Boolean)

  abstract fun source(index: Int)

  abstract fun height(fraction: Double)

  abstract fun padding(bottom: Double)

  open fun recompose(): Unit = error("${config.scenario.id} is Compose-only")

  /** The `revision` of every feature rendered at the fixture origin, in any layer. */
  abstract suspend fun renderedRevisions(): List<Int>

  abstract suspend fun settled(block: suspend () -> Unit)

  abstract fun viewport(): List<Double>

  abstract fun recordFrames(recorder: BenchmarkFrameRecorder?)

  abstract fun close()

  /** Waits for a [close] that releases resources asynchronously. */
  open suspend fun awaitClosed() {}

  /** Restore the same visible state after warming, without discarding renderer/resource caches. */
  suspend fun reset() {
    height(1.0)
    padding(0.0)
    repeat(2) { nextFrame() }
    settled {
      if (config.scenario in setOf(BenchmarkScenario.Style, BenchmarkScenario.StyleOverlay))
        withTimeout(10000) { style(0).await() }
      if (config.scenario == BenchmarkScenario.OverlayUpdate) overlay(true)
      else if (
        config.scenario in setOf(BenchmarkScenario.Images, BenchmarkScenario.ImagePreparation)
      )
        image(0)
      else if (config.scenario == BenchmarkScenario.StyleOverlay) Unit
      else if (fixture.data.isNotEmpty()) {
        layers(true)
        source(0)
        paint(0)
        visible(true)
      }
      camera(benchmarkCamera(-1.0))
    }
  }

  suspend fun run(clock: BenchmarkWorkload) {
    when (config.scenario) {
      BenchmarkScenario.MapReturn,
      BenchmarkScenario.RuntimeStartup -> error("${config.scenario.id} is driven by its host")
      BenchmarkScenario.Idle -> clock.idle()
      BenchmarkScenario.Camera,
      BenchmarkScenario.Overlays -> clock.frames { camera(tourCamera(it)) }
      BenchmarkScenario.Animation -> {
        coroutineScope {
          val animation =
            launch(start = CoroutineStart.UNDISPATCHED) {
              animate(benchmarkCamera(1.0), clock.durationMillis)
            }
          // Observe UI scheduling independently; the engine drives the camera and map frames.
          try {
            clock.frames {}
          } finally {
            animation.cancelAndJoin()
          }
        }
      }
      BenchmarkScenario.Resize -> clock.frames { height(0.75 + 0.25 * cos(it * 4 * PI)) }
      BenchmarkScenario.Padding -> clock.frames { padding((1 - cos(it * 4 * PI)) * 100) }
      BenchmarkScenario.Recompose -> clock.frames { recompose() }
      else ->
        clock.scheduled(config.rateHz) { tick ->
          val started = clock.markNow()
          val revision = (tick + 1) % 2
          val show = tick % 2 != 0
          var ready: Deferred<Unit>? = null
          when (config.scenario) {
            BenchmarkScenario.Paint -> paint(revision)
            BenchmarkScenario.SparsePaint -> sparsePaint(revision)
            BenchmarkScenario.ImageCycle -> {
              removeImages()
              registerImages()
            }
            BenchmarkScenario.ImagePreparation -> prepareImage(revision)
            BenchmarkScenario.OverlayUpdate -> overlay(show)
            BenchmarkScenario.Layout -> visible(show)
            BenchmarkScenario.Layers -> layers(show)
            BenchmarkScenario.Source,
            BenchmarkScenario.SourceLatency -> source(revision)
            BenchmarkScenario.Images -> image(revision)
            BenchmarkScenario.Style,
            BenchmarkScenario.StyleOverlay -> ready = style(revision)
            BenchmarkScenario.MapReturn,
            BenchmarkScenario.RuntimeStartup,
            BenchmarkScenario.Idle,
            BenchmarkScenario.Camera,
            BenchmarkScenario.Overlays,
            BenchmarkScenario.Animation,
            BenchmarkScenario.Resize,
            BenchmarkScenario.Padding,
            BenchmarkScenario.Recompose -> error("${config.scenario.id} is not scheduled")
          }
          val submission = started.elapsedNow().inWholeNanoseconds / 1e6
          var completion: Double? = null
          when {
            config.scenario in setOf(BenchmarkScenario.Style, BenchmarkScenario.StyleOverlay) -> {
              withTimeout(10000) { checkNotNull(ready).await() }
              if (config.scenario == BenchmarkScenario.StyleOverlay) {
                clock.completionSignal = "rendered-style-overlay"
                awaitOverlay(true)
              } else clock.completionSignal = "style-ready"
              completion = started.elapsedNow().inWholeNanoseconds / 1e6
            }
            config.scenario in
              setOf(
                BenchmarkScenario.Images,
                BenchmarkScenario.ImageCycle,
                BenchmarkScenario.ImagePreparation,
              ) -> {
              clock.completionSignal = "map-settled"
              settled {}
              completion = started.elapsedNow().inWholeNanoseconds / 1e6
            }
            config.scenario == BenchmarkScenario.OverlayUpdate -> {
              clock.completionSignal = "rendered-overlay"
              awaitOverlay(show)
              completion = started.elapsedNow().inWholeNanoseconds / 1e6
            }
            config.scenario == BenchmarkScenario.SourceLatency -> {
              clock.completionSignal = "rendered-feature-revision"
              awaitRendered { revision in it }
              completion = started.elapsedNow().inWholeNanoseconds / 1e6
            }
            config.scenario in setOf(BenchmarkScenario.Layout, BenchmarkScenario.Layers) &&
              fixture.probe -> {
              clock.completionSignal = "rendered-features"
              awaitRendered { it.isNotEmpty() == show }
              completion = started.elapsedNow().inWholeNanoseconds / 1e6
            }
          }
          clock.submitted(submission, completion)
        }
    }
  }

  private suspend fun awaitOverlay(show: Boolean) {
    withTimeout(10000) {
      do {
        nextFrame()
      } while (overlayVisible() != show)
    }
  }

  /** Polls the rendered features at the origin once per frame until [predicate] holds. */
  private suspend fun awaitRendered(predicate: (List<Int>) -> Boolean) {
    withTimeout(10000) {
      do {
        nextFrame()
      } while (!predicate(renderedRevisions()))
    }
  }
}

/** The platform hooks a run needs beyond its driver. */
class BenchmarkHost(
  val cpu: (Boolean) -> Unit,
  val collectGarbage: () -> Unit,
  val uiFrames: BenchmarkUiFrames = BenchmarkUiFrames.None,
  val status: (String) -> Unit = {},
)

/**
 * A child scope of [context] for helpers such as event collectors; a failure in one fails the run.
 */
internal fun helperScope(context: CoroutineContext) = CoroutineScope(context + Job(context[Job]))

internal fun printRunHeader(
  config: BenchmarkConfig,
  viewport: List<Double>,
  startup: StartupReport?,
) {
  println("MAP_BENCHMARK START ${config.encode()}")
  printBenchmarkBuildInfo()
  println("MAP_BENCHMARK VIEWPORT $viewport")
  println("MAP_BENCHMARK STARTUP ${BenchmarkJson.encodeToString(startup)}")
}

/**
 * Runs one measured sequence, printing the `MAP_BENCHMARK` records the runner reads. Returns the
 * failure message, or null when the run completed. [measure] runs between the CPU counter and frame
 * collection starting and stopping, and returns the workload report; [cleanup] always runs.
 */
internal suspend fun measured(
  host: BenchmarkHost,
  frameDurationMillis: Long? = null,
  timeSource: TimeSource = TimeSource.Monotonic,
  measure: suspend (BenchmarkFrameRecorder, start: () -> Unit) -> WorkloadReport,
  cleanup: suspend () -> Unit,
): String? {
  val recorder = BenchmarkFrameRecorder(timeSource)
  var measuring = false
  var report: WorkloadReport? = null
  val failure =
    try {
      report =
        measure(recorder) {
          host.collectGarbage()
          host.cpu(true)
          measuring = true
          host.uiFrames.start(frameDurationMillis)
          println("MAP_BENCHMARK MEASURE")
          recorder.start(frameDurationMillis)
        }
      host.cpu(false)
      measuring = false
      null
    } catch (e: Exception) {
      if (e is CancellationException && e !is TimeoutCancellationException) throw e
      val message = e.message ?: "Workload failed"
      println("MAP_BENCHMARK ERROR $message")
      message
    } finally {
      if (measuring) host.cpu(false)
      // Closing is part of a completed run; cancellation must also release the map.
      withContext(NonCancellable) {
        // Engine samples end with the window; frames drawn while UI metrics drain are not counted.
        host.uiFrames.end()
        recorder.stop()
        host.uiFrames.stop()
        report?.printResult()
        cleanup()
      }
    }
  if (failure == null) println("MAP_BENCHMARK DONE")
  return failure
}

/**
 * Prepares, warms up, resets, measures, and closes one map. Every host uses this sequence for every
 * workload except map return, which creates maps itself.
 */
suspend fun runBenchmark(driver: BenchmarkDriver, host: BenchmarkHost): String? {
  val config = driver.config
  val helpers = helperScope(coroutineContext)
  return measured(
    host,
    frameDurationMillis =
      config.durationMs.takeIf { config.scenario == BenchmarkScenario.Animation },
    measure = { recorder, start ->
      host.status("Loading")
      val startup = driver.prepare(helpers)
      printRunHeader(config, driver.viewport(), startup)
      host.status("Warming up")
      driver.run(BenchmarkWorkload(config.durationMs, driver.nextFrame))
      driver.reset()
      // Frame callbacks precede recomposition, so cross two frames before counting.
      host.status("Measuring")
      repeat(2) { driver.nextFrame() }
      driver.recordFrames(recorder)
      start()
      val workload = BenchmarkWorkload(config.durationMs, driver.nextFrame)
      driver.run(workload)
      workload.report()
    },
    cleanup = {
      driver.recordFrames(null)
      helpers.cancel()
      driver.close()
      driver.awaitClosed()
    },
  )
}
