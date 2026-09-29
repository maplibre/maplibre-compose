package org.maplibre.compose.style

import org.maplibre.compose.map.readBlocking
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.style.ImageStretch

/**
 * Blocks until [action] has run with the map on the binding's owner thread. Throws when the style
 * has unloaded; returns null when the loop stops first. Tests only.
 */
internal fun <T> MlnFfiStyleBinding.readMap(action: (MapHandle) -> T): T? = loop.readBlocking {
  withMap(action)
}

internal fun MlnFfiStyleBinding.imageStretches(
  id: String
): Pair<List<ImageStretch>, List<ImageStretch>>? = readMap { it.styleImageStretches(id) }
