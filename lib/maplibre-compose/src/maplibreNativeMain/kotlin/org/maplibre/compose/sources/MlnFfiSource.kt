package org.maplibre.compose.sources

import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.nativeffi.map.MapHandle

/**
 * This source's binding as MapLibre Native's own. The unloaded sentinel is shared across engines,
 * so a source that has never attached reads as an unloaded native binding here.
 */
internal val Source.ffiBinding: MlnFfiStyleBinding
  get() = binding as? MlnFfiStyleBinding ?: MlnFfiStyleBinding.UNLOADED

/**
 * Applies [update] to the live source. Returns false when the style has unloaded, which is normal
 * for a frame during a style swap.
 */
internal fun Source.mutate(update: (map: MapHandle) -> Unit): Boolean =
  ffiBinding.mutateMap(update) != null
