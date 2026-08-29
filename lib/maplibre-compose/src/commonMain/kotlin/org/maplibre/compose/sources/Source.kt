package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.SourceDefinition

/**
 * A data source for map data.
 *
 * A source describes reusable style content. Loaded-map state is owned by a generation-bound handle
 * rather than this value.
 */
public sealed class Source(internal val id: String) {

  /** This source's definition as style JSON, used to install it and to answer value reads. */
  internal abstract fun toJson(): JsonObject

  /** An immutable snapshot that can be installed in any compatible loaded style. */
  internal open fun definition(): SourceDefinition = SourceDefinition.Json(id, toJson())

  public val attributionHtml: String
    get() = (toJson()["attribution"] as? JsonPrimitive)?.content.orEmpty()

  override fun toString(): String = "${this::class.simpleName}(id=\"$id\")"
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
  LaunchedEffect(source, update, !node.style.isLoaded) {
    if (node.style.isLoaded) {
      source.update()
      node.sourceManager.updateReference(source)
    }
  }
  return source
}

public object SourceDefaults {
  public const val MIN_ZOOM: Int = 0
  public const val MAX_ZOOM: Int = 18
  public const val RASTER_TILE_SIZE: Int = 512
}
