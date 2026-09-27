package org.maplibre.compose.benchmark

import kotlin.time.TimeSource
import kotlinx.coroutines.*

interface BenchmarkUiFrames {
  fun start()

  suspend fun stop()

  object None : BenchmarkUiFrames {
    override fun start() {}

    override suspend fun stop() {}
  }
}

/** A fresh map per return, with prepared data and warm process/resource caches. */
suspend fun runMapReturnBenchmark(
  config: BenchmarkConfig,
  nextFrame: suspend () -> Long,
  mount: suspend (BenchmarkFrameRecorder) -> List<Double>,
  unmount: suspend () -> Unit,
  cover: (Double) -> Unit,
  cpu: (Boolean) -> Unit,
  collectGarbage: () -> Unit,
  uiFrames: BenchmarkUiFrames,
) {
  val recorder = BenchmarkFrameRecorder()
  var measuring = false
  try {
    // Prime code and packaged resources, but never retain the measured map or its style.
    cover(0.0)
    val viewport = mount(recorder)
    unmount()
    delay(500)
    println("MAP_BENCHMARK START ${config.encode()}")
    printBenchmarkBuildInfo()
    println("MAP_BENCHMARK VIEWPORT $viewport")
    collectGarbage()
    cpu(true)
    measuring = true
    recorder.start()
    uiFrames.start()
    println("MAP_BENCHMARK MEASURE")
    val clock = BenchmarkWorkload(config.durationMs, nextFrame)
    clock.completionSignal = "content-rendered-idle"
    clock.scheduled(config.rateHz) {
      val start = TimeSource.Monotonic.markNow()
      coroutineScope {
        launch(start = CoroutineStart.UNDISPATCHED) {
          val first = nextFrame()
          do {
            val progress = ((nextFrame() - first) / 300_000_000.0).coerceIn(0.0, 1.0)
            cover(progress)
          } while (progress < 1.0)
        }
        mount(recorder)
        clock.submitted(completionMs = start.elapsedNow().inWholeNanoseconds / 1e6)
      }
      // Keep the map visible briefly, then dispose it behind the opaque cover.
      delay(200)
      cover(0.0)
      repeat(2) { nextFrame() }
      unmount()
      delay(500)
    }
    val report = clock.report()
    cpu(false)
    measuring = false
    uiFrames.stop()
    recorder.stop()
    report.printResult()
    println("MAP_BENCHMARK DONE")
  } catch (e: Exception) {
    if (e is CancellationException && e !is TimeoutCancellationException) throw e
    println("MAP_BENCHMARK ERROR ${e.message}")
  } finally {
    if (measuring) cpu(false)
    withContext(NonCancellable) {
      uiFrames.stop()
      recorder.stop()
      unmount()
    }
  }
}
