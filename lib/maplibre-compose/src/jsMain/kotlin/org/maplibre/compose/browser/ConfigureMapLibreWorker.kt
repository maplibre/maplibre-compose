package org.maplibre.compose.browser

import org.maplibre.compose.gljs.GlJsRuntime

/**
 * Sets the MapLibre GL JS worker URL without initializing Compose graphics.
 *
 * Call before creating any browser maps or snapshotters to self-host the worker. Otherwise the
 * library uses the bundled MapLibre GL JS version's worker on jsDelivr. The first worker
 * configuration wins; later calls, including [installMapLibreCompose], do not change it.
 *
 * Serve `maplibre-gl-worker.mjs` and its sibling `maplibre-gl-shared.mjs` from the same MapLibre GL
 * JS version as the library. Relative URLs resolve against the page URL.
 */
public fun configureMapLibreWorker(workerUrl: String) {
  GlJsRuntime.pointAtWorker(workerUrl)
}
