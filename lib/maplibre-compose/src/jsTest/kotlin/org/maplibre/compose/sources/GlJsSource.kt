package org.maplibre.compose.sources

import org.maplibre.compose.style.GlJsStyleBinding

/** This source's binding as MapLibre GL JS's own, or null when it has never attached to one. */
internal val Source.glJsBinding: GlJsStyleBinding?
  get() = binding as? GlJsStyleBinding
