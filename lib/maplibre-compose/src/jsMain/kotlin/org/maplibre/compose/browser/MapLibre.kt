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
   *     MapLibre.initialize()
   *     ComposeViewport(document.body!!) { App() }
   *   }
   * }
   * ```
   *
   * By default the MapLibre GL JS 6 worker is loaded from the jsDelivr CDN at the same version as
   * the bundled library, so no bundler setup is needed. Pass [workerUrl] to self-host the worker or
   * pin a different version.
   *
   * Repeat calls are ignored.
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
