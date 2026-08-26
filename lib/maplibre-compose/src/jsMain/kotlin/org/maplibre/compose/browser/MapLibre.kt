package org.maplibre.compose.browser

import org.maplibre.compose.gljs.DEFAULT_WORKER_URL
import org.maplibre.compose.gljs.GlJsRuntime

/** Browser configuration for MapLibre Compose. */
public object MapLibre {
  /**
   * Sets the MapLibre GL JS worker URL. Call this before the first map is composed.
   *
   * [workerUrl] defaults to the bundled MapLibre GL JS worker on jsDelivr. Later calls are ignored.
   */
  public fun configure(workerUrl: String = DEFAULT_WORKER_URL) {
    GlJsRuntime.pointAtWorker(workerUrl)
  }
}
