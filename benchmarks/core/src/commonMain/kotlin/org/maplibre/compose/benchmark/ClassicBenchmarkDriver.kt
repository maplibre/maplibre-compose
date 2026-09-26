package org.maplibre.compose.benchmark

import kotlin.math.PI
import kotlin.math.cos
import kotlin.time.TimeSource
import kotlinx.coroutines.*

/** SDK operations; the shared driver owns workload order and completion semantics. */
abstract class ClassicBenchmarkDriver(
  val fixture: PreparedBenchmarkFixture,
  val nextFrame: suspend () -> Long,
) {
  val config = fixture.config

  abstract suspend fun prepare()

  abstract fun camera(value: BenchmarkCamera)

  abstract suspend fun animate(value: BenchmarkCamera, durationMs: Long)

  abstract fun style(index: Int): Deferred<Unit>

  abstract fun image(index: Int)

  abstract fun layers(show: Boolean)

  abstract fun paint(index: Int)

  abstract fun visible(show: Boolean)

  abstract fun source(index: Int)

  abstract fun height(fraction: Double)

  abstract fun padding(bottom: Double)

  abstract fun hasRevision(revision: Int): Boolean

  abstract suspend fun settled(block: suspend () -> Unit)

  abstract fun viewport(): List<Double>

  abstract fun recordFrames(recorder: BenchmarkFrameRecorder?)

  abstract fun close()

  suspend fun reset() {
    height(1.0)
    padding(0.0)
    repeat(2) { nextFrame() }
    settled {
      if (config.scenario == BenchmarkScenario.Style) withTimeout(10000) { style(0).await() }
      if (config.scenario == BenchmarkScenario.Images) image(0)
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
      BenchmarkScenario.Idle -> clock.idle()
      BenchmarkScenario.Camera -> clock.frames { camera(tourCamera(it)) }
      BenchmarkScenario.Animation -> {
        repeat(4) { index ->
          withTimeout(clock.durationMillis + 10000) {
            animate(benchmarkCamera(if (index % 2 == 0) 1.0 else -1.0), clock.durationMillis / 4)
          }
          clock.submitted()
        }
        clock.idle()
      }
      BenchmarkScenario.Resize -> clock.frames { height(0.75 + 0.25 * cos(it * 4 * PI)) }
      BenchmarkScenario.Padding -> clock.frames { padding((1 - cos(it * 4 * PI)) * 100) }
      BenchmarkScenario.Recompose -> error("Recomposition is Compose-only")
      else ->
        clock.scheduled(config.rateHz) { tick ->
          val started = TimeSource.Monotonic.markNow()
          val revision = (tick + 1) % 2
          var ready: Deferred<Unit>? = null
          when (config.scenario) {
            BenchmarkScenario.Paint -> paint(revision)
            BenchmarkScenario.Layout -> visible(tick % 2 != 0)
            BenchmarkScenario.Layers -> layers(tick % 2 != 0)
            BenchmarkScenario.Source,
            BenchmarkScenario.SourceLatency -> source(revision)
            BenchmarkScenario.Images -> image(revision)
            BenchmarkScenario.Style -> ready = style(revision)
          }
          val submission = started.elapsedNow().inWholeNanoseconds / 1e6
          var completion: Double? = null
          if (config.scenario == BenchmarkScenario.Style) {
            clock.completionSignal = "style-ready"
            withTimeout(10000) { checkNotNull(ready).await() }
            completion = started.elapsedNow().inWholeNanoseconds / 1e6
          } else if (config.scenario == BenchmarkScenario.SourceLatency) {
            clock.completionSignal = "rendered-feature-revision"
            withTimeout(10000) {
              do {
                nextFrame()
              } while (!hasRevision(revision))
            }
            completion = started.elapsedNow().inWholeNanoseconds / 1e6
          }
          clock.submitted(submission, completion)
        }
    }
  }
}

/** Every classic host uses the same warm-up, reset, measurement, and cleanup sequence. */
suspend fun runClassicBenchmark(
  driver: ClassicBenchmarkDriver,
  cpu: (Boolean) -> Unit,
  collectGarbage: () -> Unit,
) {
  val config = driver.config
  val recorder = BenchmarkFrameRecorder()
  var measuring = false
  try {
    driver.prepare()
    println("MAP_BENCHMARK START ${config.encode()}")
    println("MAP_BENCHMARK VIEWPORT ${driver.viewport()}")
    driver.run(BenchmarkWorkload(config.durationMs, driver.nextFrame))
    driver.reset()
    repeat(2) { driver.nextFrame() }
    collectGarbage()
    cpu(true)
    measuring = true
    recorder.start()
    driver.recordFrames(recorder)
    println("MAP_BENCHMARK MEASURE")
    val workload = BenchmarkWorkload(config.durationMs, driver.nextFrame)
    driver.run(workload)
    val report = workload.report()
    cpu(false)
    measuring = false
    driver.recordFrames(null)
    recorder.stop()
    report.printResult()
    driver.close()
    println("MAP_BENCHMARK DONE")
  } catch (e: Exception) {
    if (e is CancellationException && e !is TimeoutCancellationException) throw e
    println("MAP_BENCHMARK ERROR ${e.message ?: "Workload failed"}")
  } finally {
    if (measuring) cpu(false)
    driver.recordFrames(null)
    withContext(NonCancellable) { recorder.stop() }
    driver.close()
  }
}
