package org.maplibre.compose.browser

import org.maplibre.compose.gljs.GlJsRuntime
import org.maplibre.compose.gljs.SkikoGpuBridge

/** Process-wide entry point for configuring MapLibre Compose in the browser. */
public object MapLibre {
  /**
   * Prepares the page to composite maps, and must be called before Compose starts.
   *
   * ```kt
   * fun main() {
   *   onWasmReady {
   *     MapLibre.initialize()
   *     ComposeViewport(document.body!!) { App() }
   *   }
   * }
   * ```
   *
   * Repeat calls are ignored. Also points MapLibre GL JS at the web worker file that webpack copies
   * next to the page.
   *
   * @throws IllegalStateException if skiko is not loaded yet. Call this inside `onWasmReady`.
   */
  public fun initialize() {
    check(SkikoGpuBridge.install()) {
      "MapLibre.initialize() ran before skiko finished loading, so Compose's graphics context " +
        "cannot be reached. Call it inside onWasmReady, immediately before ComposeViewport."
    }
    GlJsRuntime.pointAtBundledWorker()
  }
}
