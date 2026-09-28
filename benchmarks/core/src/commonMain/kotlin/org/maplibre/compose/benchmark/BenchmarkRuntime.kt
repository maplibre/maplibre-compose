package org.maplibre.compose.benchmark

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Reopens one empty local cache after an unmeasured priming instance. Submission measures the
 * constructor; completion measures observed readiness from the same start. No map is created.
 */
suspend fun <T : Any> runRuntimeBenchmark(
  config: BenchmarkConfig,
  nextFrame: suspend () -> Long,
  viewport: List<Double>,
  create: () -> T,
  awaitReady: suspend (T) -> Unit,
  close: (T) -> Unit,
  awaitClosed: suspend (T) -> Unit,
  host: BenchmarkHost,
): String? {
  require(config.scenario == BenchmarkScenario.RuntimeStartup)
  var current: T? = null

  suspend fun dispose(clock: BenchmarkWorkload? = null) {
    val runtime = current ?: return
    current = null
    withContext(NonCancellable) {
      if (clock != null)
        clock.close({ close(runtime) }, { withTimeout(15000) { awaitClosed(runtime) } })
      else {
        try {
          close(runtime)
        } finally {
          withTimeout(15000) { awaitClosed(runtime) }
        }
      }
    }
  }

  return measured(
    host,
    measure = { _, start ->
      host.status("Priming runtime")
      current = create()
      withTimeout(15000) { awaitReady(checkNotNull(current)) }
      dispose()
      printRunHeader(config, viewport, startup = null)
      repeat(2) { nextFrame() }
      host.status("Measuring runtime readiness")
      start()
      val clock = BenchmarkWorkload(config.durationMs, nextFrame)
      clock.completionSignal = "offline-ready"
      clock.scheduled(config.rateHz) {
        val started = clock.markNow()
        val runtime = create().also { current = it }
        val submission = started.elapsedNow().inWholeNanoseconds / 1e6
        withTimeout(15000) { awaitReady(runtime) }
        clock.submitted(submission, started.elapsedNow().inWholeNanoseconds / 1e6)
        dispose(clock)
      }
      clock.report()
    },
    cleanup = { dispose() },
  )
}
