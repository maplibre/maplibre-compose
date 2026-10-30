package org.maplibre.compose.browser

import org.maplibre.compose.gljs.DefaultWorkerUrl

/**
 * Sets the MapLibre GL JS worker URL before the first map is created. Later calls are ignored.
 *
 * Compose provides the shared WebGL render target.
 *
 * @param workerUrl The MapLibre GL JS worker URL. Defaults to the bundled patched worker; see
 *   [configureMaplibreWorker] to self-host it.
 */
public fun installMaplibreCompose(workerUrl: String = DefaultWorkerUrl) {
  configureMaplibreWorker(workerUrl)
}
