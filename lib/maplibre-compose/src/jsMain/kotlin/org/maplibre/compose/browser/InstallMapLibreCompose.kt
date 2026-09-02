package org.maplibre.compose.browser

import org.maplibre.compose.gljs.DEFAULT_WORKER_URL
import org.maplibre.compose.gljs.GlJsRuntime
import org.maplibre.compose.gljs.SkikoGpuBridge

/**
 * Installs the browser graphics integration and sets the MapLibre GL JS worker URL.
 *
 * Call this inside `onWasmReady`, before Compose starts. [workerUrl] defaults to the bundled
 * MapLibre GL JS worker on jsDelivr. Later calls are ignored.
 *
 * @throws IllegalStateException if skiko has not published its exports yet.
 */
public fun installMapLibreCompose(workerUrl: String = DEFAULT_WORKER_URL) {
  check(SkikoGpuBridge.install()) {
    "installMapLibreCompose() ran before skiko finished loading, so Compose's graphics context " +
      "cannot be reached. Call it inside onWasmReady, immediately before ComposeViewport."
  }
  GlJsRuntime.pointAtWorker(workerUrl)
}
