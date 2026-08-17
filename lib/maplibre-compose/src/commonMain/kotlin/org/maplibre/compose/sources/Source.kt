package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import org.maplibre.compose.style.LocalStyleNode

/**
 * A data source for map data.
 *
 * One instance may belong to only one loaded map style at a time. Create or remember a separate
 * source inside each [MaplibreMap][org.maplibre.compose.map.MaplibreMap] that uses it.
 */
public expect sealed class Source {
  internal val id: String
  public val attributionHtml: String
}

/**
 * Get the source with the given [id] from the base style specified via the `baseStyle` parameter in
 * [MaplibreMap][org.maplibre.compose.map.MaplibreMap].
 *
 * @throws IllegalStateException if the source does not exist
 */
@Composable
public fun getBaseSource(id: String): Source? {
  val node = LocalStyleNode.current
  return remember(node, id) { node.sourceManager.getBaseSource(id) }
}

@Composable
internal fun <T : Source> rememberUserSource(factory: (String) -> T, update: T.() -> Unit): T {
  val node = LocalStyleNode.current
  val source = remember(node) { factory(node.sourceManager.nextId()) }
  // SideEffect, not LaunchedEffect: applies synchronously with composition, matching the
  // ordering LayerNode's ComposeNode update block gets — see SafeStyle's kdoc.
  SideEffect { if (!node.style.isUnloaded) source.update() }
  return source
}

public object SourceDefaults {
  public const val MIN_ZOOM: Int = 0
  public const val MAX_ZOOM: Int = 18
  public const val RASTER_TILE_SIZE: Int = 512
}
