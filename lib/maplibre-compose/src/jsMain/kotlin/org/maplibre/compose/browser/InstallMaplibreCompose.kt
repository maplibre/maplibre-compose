package org.maplibre.compose.browser

import org.maplibre.compose.gljs.DefaultWorkerUrl
import org.maplibre.compose.gljs.SkikoGpuBridge

/**
 * Installs the browser graphics integration and sets the MapLibre GL JS worker URL.
 *
 * Call this inside `onWasmReady`, before Compose starts. [workerUrl] defaults to the bundled
 * patched MapLibre GL JS worker. Later calls are ignored.
 *
 * Maps draw into the graphics context that Compose creates when it starts, and this call is what
 * gives them access to it. Without this call, or when Compose started before it, maps load but
 * never appear, and each map logs a debug message that says why it is waiting.
 *
 * @throws IllegalStateException if skiko has not published its exports yet.
 */
public fun installMaplibreCompose(workerUrl: String = DefaultWorkerUrl) {
  check(SkikoGpuBridge.install()) {
    "installMaplibreCompose() ran before skiko finished loading, so Compose's graphics context " +
      "cannot be reached. Call it inside onWasmReady, immediately before ComposeViewport."
  }
  configureMaplibreWorker(workerUrl)
}
