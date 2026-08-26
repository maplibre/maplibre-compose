package org.maplibre.compose.sources

import org.maplibre.compose.style.MlnFfiStyleBinding

/** This source's binding as MapLibre Native's own, or null when it is not bound to one. */
internal val Source.ffiBinding: MlnFfiStyleBinding?
  get() = binding as? MlnFfiStyleBinding
