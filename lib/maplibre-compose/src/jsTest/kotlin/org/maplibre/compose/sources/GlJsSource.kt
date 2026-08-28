package org.maplibre.compose.sources

import org.maplibre.compose.style.GlJsStyleBinding
import org.maplibre.compose.style.StyleBinding

/** This binding as MapLibre GL JS's own, or null when it is not a browser style. */
internal val StyleBinding.glJs: GlJsStyleBinding?
  get() = this as? GlJsStyleBinding
