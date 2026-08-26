package org.maplibre.compose.browser

import org.maplibre.compose.gljs.DEFAULT_WORKER_URL
import org.maplibre.compose.gljs.GlJsRuntime

/**
 * Sets the MapLibre GL JS worker URL before the first map is created.
 *
 * [workerUrl] defaults to the bundled MapLibre GL JS worker on jsDelivr. Later calls are ignored.
 */
public fun installMapLibreCompose(workerUrl: String = DEFAULT_WORKER_URL) {
  GlJsRuntime.pointAtWorker(workerUrl)
}
