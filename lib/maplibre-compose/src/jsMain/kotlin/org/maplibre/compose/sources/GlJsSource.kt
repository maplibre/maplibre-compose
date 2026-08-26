package org.maplibre.compose.sources

import org.maplibre.compose.gljs.MaplibreMap
import org.maplibre.compose.style.GlJsStyleBinding

/** This source's binding as MapLibre GL JS's own, or null when it has never attached to one. */
internal val Source.glJsBinding: GlJsStyleBinding?
  get() = binding as? GlJsStyleBinding

/** False when the style has unloaded, which is normal for a frame during a style swap. */
internal fun Source.mutate(update: (map: MaplibreMap) -> Unit): Boolean =
  glJsBinding?.withMap(update) != null

internal fun <T : Any> Source.liveSource(): T? = glJsBinding?.withMap { map ->
  map.getSource<T>(id)
}
