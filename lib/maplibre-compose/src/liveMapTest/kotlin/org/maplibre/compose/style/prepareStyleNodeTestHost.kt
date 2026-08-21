package org.maplibre.compose.style

/** Loads the native runtime on MapLibre Native hosts. The browser has nothing to load. */
internal expect fun prepareStyleNodeTestHost()
