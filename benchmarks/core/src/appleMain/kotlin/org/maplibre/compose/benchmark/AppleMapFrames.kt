@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.maplibre.compose.benchmark

import org.maplibre.compose.benchmark.metal.BenchmarkMetalFrames
import platform.QuartzCore.CAMetalLayer
import platform.darwin.NSObject

/** Observes the map's actual Metal presentations; installed before warm-up acquires drawables. */
class AppleMapFrames(private val layer: () -> Pair<CAMetalLayer, NSObject>) : BenchmarkUiFrames {
  private var capture: BenchmarkMetalFrames? = null

  init {
    if (BenchmarkMetalFrames.available()) BenchmarkMetalFrames.install()
  }

  override fun start(durationMillis: Long?) {
    if (!BenchmarkMetalFrames.available()) return
    check(capture == null)
    val (layer, screen) = layer()
    capture =
      BenchmarkMetalFrames(listOf(layer), screen).also {
        it.startDuration(checkNotNull(durationMillis).toDouble())
      }
  }

  override fun end() {
    capture?.end()
  }

  override suspend fun stop() {
    val capture = capture ?: return
    println("MAP_BENCHMARK PRESENTATION " + capture.report())
    this.capture = null
  }
}
