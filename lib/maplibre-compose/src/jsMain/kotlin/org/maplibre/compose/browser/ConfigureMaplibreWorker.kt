package org.maplibre.compose.browser

import org.maplibre.compose.gljs.GlJsRuntime

/**
 * Sets the MapLibre GL JS worker URL without initializing Compose graphics.
 *
 * Call before creating any browser maps or snapshotters to self-host the worker. Otherwise the
 * library creates a Blob URL for its embedded, patched worker. The first worker configuration wins;
 * later calls, including [installMaplibreCompose], do not change it.
 *
 * Serve `maplibre-gl/worker.mjs` extracted from this library's JS KLIB. It includes the matching
 * engine patches and has no sibling imports. Relative URLs resolve against the page URL.
 */
public fun configureMaplibreWorker(workerUrl: String) {
  GlJsRuntime.pointAtWorker(workerUrl)
}
