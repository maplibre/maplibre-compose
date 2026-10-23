package org.maplibre.compose.style

import org.maplibre.compose.map.styleImageStretches
import org.maplibre.nativeffi.generated.ImageStretch
import org.maplibre.nativeffi.generated.MapHandle

/** Runs [action] with the binding's map. Throws when the style has unloaded. Tests only. */
internal fun <T> MlnFfiStyleBinding.readMap(action: (MapHandle) -> T): T {
  requireCurrent()
  return action(mapForTest)
}

internal fun MlnFfiStyleBinding.imageStretches(
  id: String,
): Pair<List<ImageStretch>, List<ImageStretch>>? = readMap { it.styleImageStretches(id) }
