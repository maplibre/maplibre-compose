package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.map.MapState
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleMutationException

/**
 * A data source for map data.
 *
 * The object is a definition: its id and payload. [MapState][org.maplibre.compose.map.MapState] is
 * the only writer that installs it on a style.
 */
public sealed class Source(internal val id: String) {

  /**
   * This source's definition as style JSON, used to add it and to answer reads before a style loads
   * it.
   */
  internal abstract fun toJson(): JsonObject

  /**
   * The [MapState] that currently owns this definition, or null. Live mutations enqueue commands
   * through it.
   */
  internal var map: MapState? = null

  public val attributionHtml: String
    get() = (toJson()["attribution"] as? JsonPrimitive)?.content.orEmpty()

  /**
   * Installs this definition on [binding] by id. The source does not store the binding.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   */
  internal open fun addTo(binding: StyleBinding): Boolean = binding.addSource(id, toJson())

  /**
   * Installs this definition on [binding], wrapping a refused add so the caller names the source.
   */
  internal fun install(binding: StyleBinding): Boolean =
    try {
      addTo(binding)
    } catch (error: StyleMutationException) {
      throw IllegalStateException(
        "Could not add source '$id' of type " +
          "'${(toJson()["type"] as? JsonPrimitive)?.content}': ${error.message}",
        error,
      )
    }

  override fun toString(): String = "${this::class.simpleName}(id=\"$id\")"
}

/**
 * Returns the source with the given [id] from the base style that
 * [MapState.baseStyle][org.maplibre.compose.map.MapState.baseStyle] selects, or null when the
 * loaded style has no such source.
 *
 * After a style swap, this returns the new base style's source.
 * [MapState.sources][org.maplibre.compose.map.MapState.sources] reads the same sources outside the
 * style composition.
 */
@Composable
public fun getBaseSource(id: String): Source? {
  val node = LocalStyleNode.current
  return remember(node, node.binding, id) { node.sourceManager.getBaseSource(id) }
}

@Composable
internal fun <T : Source> rememberUserSource(factory: (String) -> T, update: T.() -> Unit): T {
  val node = LocalStyleNode.current
  val source = remember(node) { factory(node.sourceManager.nextId()) }
  source.update()
  return source
}

public object SourceDefaults {
  public const val MIN_ZOOM: Int = 0
  public const val MAX_ZOOM: Int = 18
  public const val RASTER_TILE_SIZE: Int = 512
}
