package org.maplibre.compose.benchmark

/**
 * Window frame timings from the platform, collected during the measured window separately from the
 * engine's render events. They describe actual window draws, which may be absent when the map
 * renders to a separate surface.
 */
interface BenchmarkUiFrames {
  /** Collects a fixed interval when [durationMillis] is supplied. */
  fun start(durationMillis: Long? = null)

  /** Marks the end of measured work before reports are printed and queued metrics drain. */
  fun end() {}

  /** Stops collecting and prints the `UISTATS` and `UIFRAMES` records. */
  suspend fun stop()

  /** For platforms without window frame timings. */
  object None : BenchmarkUiFrames {
    override fun start(durationMillis: Long?) {}

    override suspend fun stop() {}
  }
}
