package org.maplibre.compose.sources

import org.maplibre.compose.style.MlnFfiStyleBinding

/**
 * This source's binding as MapLibre Native's own. The unloaded sentinel is shared across engines,
 * so a source that has never attached reads as an unloaded native binding here.
 */
internal val Source.ffiBinding: MlnFfiStyleBinding
  get() = binding as? MlnFfiStyleBinding ?: MlnFfiStyleBinding.UNLOADED
