package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import org.maplibre.compose.style.LocalStyleNode

/** A data source for map data */
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
  LaunchedEffect(source, update, node.style.isUnloaded) {
    if (!node.style.isUnloaded) source.update()
  }
  return source
}

public object SourceDefaults {
  public const val MIN_ZOOM: Int = 0
  public const val MAX_ZOOM: Int = 18
  public const val RASTER_TILE_SIZE: Int = 512
}
