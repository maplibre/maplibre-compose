package org.maplibre.compose.browser

import org.maplibre.compose.gljs.DEFAULT_WORKER_URL
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
   *     MapLibre.initialize(workerUrl = "/maplibre-gl-worker.mjs")
   *     ComposeViewport(document.body!!) { App() }
   *   }
   * }
   * ```
   *
   * Repeat calls are ignored. [workerUrl] is the MapLibre GL JS 6 module worker. A path that starts
   * with `/` is resolved against the origin, so a history route still finds webpack's copy at the
   * site root. Pass a full URL when the worker files are not there.
   *
   * @throws IllegalStateException if skiko is not loaded yet. Call this inside `onWasmReady`.
   */
  public fun initialize(workerUrl: String = DEFAULT_WORKER_URL) {
    check(SkikoGpuBridge.install()) {
      "MapLibre.initialize() ran before skiko finished loading, so Compose's graphics context " +
        "cannot be reached. Call it inside onWasmReady, immediately before ComposeViewport."
    }
    GlJsRuntime.pointAtWorker(workerUrl)
  }
}
