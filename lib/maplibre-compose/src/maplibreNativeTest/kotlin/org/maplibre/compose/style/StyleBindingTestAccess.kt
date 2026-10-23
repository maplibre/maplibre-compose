package org.maplibre.compose.style

import org.maplibre.nativeffi.generated.MapHandle

/** Runs [action] with the binding's map. Throws when the style has unloaded. Tests only. */
internal inline fun <T> MlnFfiStyleBinding.readMap(action: (MapHandle) -> T): T {
  requireCurrent()
  return action(mapForTest)
}
