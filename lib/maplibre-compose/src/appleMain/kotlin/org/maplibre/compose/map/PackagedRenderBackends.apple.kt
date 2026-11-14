package org.maplibre.compose.map

import org.maplibre.compose.mlnffi.MapRenderBackend

/** Apple platforms link the runtime into the app and render with Metal. */
internal actual fun packagedRenderBackends(): Set<MapRenderBackend> = emptySet()
