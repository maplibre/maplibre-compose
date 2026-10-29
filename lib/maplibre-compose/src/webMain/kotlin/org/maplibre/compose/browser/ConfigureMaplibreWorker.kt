package org.maplibre.compose.browser

import org.maplibre.compose.gljs.GlJsRuntime

/**
 * Sets the MapLibre GL JS worker URL without initializing Compose graphics.
 *
 * Call before creating any browser maps or snapshotters to self-host the worker. The first worker
 * configuration wins; later calls, including [installMaplibreCompose], do not change it.
 *
 * Self-host the worker when the page's Content Security Policy does not allow workers from `blob:`
 * URLs. Serve it from the page's origin.
 *
 * Serve `maplibre-gl/worker.mjs` extracted from this library's JS KLIB, from the same release as
 * the library. It has no sibling imports.
 *
 * @param workerUrl The URL of the worker module. A relative URL resolves against the page URL.
 */
public fun configureMaplibreWorker(workerUrl: String) {
  GlJsRuntime.pointAtWorker(workerUrl)
}
