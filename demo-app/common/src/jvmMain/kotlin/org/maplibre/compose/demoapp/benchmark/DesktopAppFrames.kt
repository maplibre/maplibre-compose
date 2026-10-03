package org.maplibre.compose.demoapp.benchmark

import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Window
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import org.maplibre.compose.benchmark.BenchmarkUiFrames

/** Measures Compose's actual work recording a window frame, without requesting redraws. */
internal class DesktopAppFrames : BenchmarkUiFrames {
  private var layer: SkiaLayer? = null
  private var delegate: SkikoRenderDelegate? = null
  private val times = mutableListOf<Double>()
  private var start = 0L
  private var end = Long.MAX_VALUE

  override fun start(durationMillis: Long?) {
    // Nucleus runs Compose on its own event loop.
    if (!EventQueue.isDispatchThread()) return
    check(layer == null)
    times.clear()
    val layers = Window.getWindows().filter { it.isVisible }.flatMap(::layers)
    // Nucleus does not host Compose in an AWT SkiaLayer.
    if (layers.isEmpty()) return
    val layer = layers.single()
    val original = checkNotNull(layer.renderDelegate)
    this.layer = layer
    delegate = original
    start = System.nanoTime()
    end = durationMillis?.let { start + it * 1_000_000 } ?: Long.MAX_VALUE
    layer.renderDelegate = SkikoRenderDelegate { canvas, width, height, nanoTime ->
      val before = System.nanoTime()
      try {
        original.onRender(canvas, width, height, nanoTime)
      } finally {
        if (before in start until end) times += (System.nanoTime() - before) / 1e6
      }
    }
  }

  override fun end() {
    if (layer == null) return
    check(EventQueue.isDispatchThread())
    end = minOf(end, System.nanoTime())
    layer?.renderDelegate = delegate
    layer = null
    delegate = null
  }

  override suspend fun stop() {
    times.chunked(32).forEach { println("MAP_BENCHMARK APPDRAW " + it.joinToString(",", "[", "]")) }
    println("MAP_BENCHMARK APPDRAWSTATS {\"frames\":${times.size}}")
  }

  private fun layers(component: Component): List<SkiaLayer> =
    if (component is SkiaLayer) listOf(component)
    else if (component is Container) component.components.flatMap(::layers) else emptyList()
}
