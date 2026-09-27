package org.maplibre.compose.benchmark

/**
 * Window frame timings from the platform, collected during the measured window separately from the
 * engine's render events. They show UI thread stalls the map causes, such as during map creation,
 * that the workload's own frame callbacks cannot see.
 */
interface BenchmarkUiFrames {
  fun start()

  /** Stops collecting and prints the `UISTATS` and `UIFRAMES` records. */
  suspend fun stop()

  /** For platforms without window frame timings. */
  object None : BenchmarkUiFrames {
    override fun start() {}

    override suspend fun stop() {}
  }
}
