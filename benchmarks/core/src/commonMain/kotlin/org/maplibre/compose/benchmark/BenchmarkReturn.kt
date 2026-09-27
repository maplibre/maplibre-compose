package org.maplibre.compose.benchmark

import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.*

/** A map the host created for one return, with the helpers its driver launched. */
private class Returned(val driver: BenchmarkDriver, val helpers: CoroutineScope)

/**
 * Creates a populated map again and again, each time behind a panel that slides away over 300 ms,
 * as an app screen with a map opens. Completion is the time from creating the map until its content
 * rendered and settled. One unmeasured map primes code and resource caches; every measured return
 * still builds a live map and style. The window frame timings, where the platform has them, show
 * whether creation stalled the UI thread during the transition.
 *
 * [mount] creates a map and its driver without preparing it; [unmount] disposes that map and waits
 * for it to close. [cover] positions the panel: 0 covers the map, 1 has slid away.
 */
suspend fun runMapReturnBenchmark(
  config: BenchmarkConfig,
  nextFrame: suspend () -> Long,
  mount: suspend () -> BenchmarkDriver,
  unmount: suspend (BenchmarkDriver) -> Unit,
  cover: (Double) -> Unit,
  host: BenchmarkHost,
): String? {
  require(config.scenario == BenchmarkScenario.MapReturn)
  // Helpers belong to the run, not to the scope of the return that created them.
  val context = coroutineContext
  var current: Returned? = null

  suspend fun create(recorder: BenchmarkFrameRecorder?): StartupReport {
    val driver = mount()
    val helpers = helperScope(context)
    current = Returned(driver, helpers)
    driver.recordFrames(recorder)
    return driver.prepare(helpers)
  }

  suspend fun dispose() {
    val returned = current ?: return
    current = null
    returned.driver.recordFrames(null)
    returned.helpers.cancel()
    unmount(returned.driver)
  }

  return measured(
    host,
    measure = { recorder, start ->
      host.status("Priming")
      cover(0.0)
      val startup = create(null)
      val viewport = current!!.driver.viewport()
      dispose()
      delay(500)
      printRunHeader(config, viewport, startup)
      host.status("Measuring returns")
      start()
      val clock = BenchmarkWorkload(config.durationMs, nextFrame)
      clock.completionSignal = "map-settled"
      clock.scheduled(config.rateHz) {
        val started = TimeSource.Monotonic.markNow()
        coroutineScope {
          launch(start = CoroutineStart.UNDISPATCHED) {
            val first = nextFrame()
            do {
              val progress = ((nextFrame() - first) / 300_000_000.0).coerceIn(0.0, 1.0)
              cover(progress)
            } while (progress < 1.0)
          }
          create(recorder)
          clock.submitted(completionMs = started.elapsedNow().inWholeNanoseconds / 1e6)
        }
        // Keep the map visible briefly, then dispose it behind the panel.
        delay(200)
        cover(0.0)
        repeat(2) { nextFrame() }
        dispose()
        delay(500)
      }
      clock.report()
    },
    cleanup = { dispose() },
  )
}
