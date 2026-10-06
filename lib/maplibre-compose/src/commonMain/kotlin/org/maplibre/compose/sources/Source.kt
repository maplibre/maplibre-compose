package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.util.formatToString

/**
 * A data source for map data.
 *
 * A source describes reusable style content. Loaded-map state is owned by a generation-bound handle
 * rather than this value.
 *
 * Values may be added in minor releases; use an `else` branch when matching. [getBaseSource] can
 * return a source whose style-spec type has no public class in this version.
 */
public sealed class Source(internal val id: String) {

  /** This source's definition as style JSON, used to install it and to answer value reads. */
  internal abstract fun toJson(): JsonObject

  /** An immutable snapshot that can be installed in any compatible loaded style. */
  internal open fun definition(): SourceDefinition = SourceDefinition.Json(id, toJson())

  public val attributionHtml: String
    get() = (toJson()["attribution"] as? JsonPrimitive)?.content.orEmpty()

  override fun toString(): String = formatToString(this::class.simpleName.orEmpty(), "id" to id)
}

/**
 * A source of vector features: tiled vector data, GeoJSON, or application-supplied tiles.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed class VectorSource(id: String) : Source(id)

/**
 * A source of raster imagery: tiled pictures or a positioned image.
 *
 * Values may be added in minor releases; use an `else` branch when matching. [getBaseSource]
 * returns a base-style video or canvas source as a [RasterSource] with no public class, which a
 * [RasterLayer][org.maplibre.compose.layers.RasterLayer] can draw.
 */
public sealed class RasterSource(id: String) : Source(id)

/**
 * A base-style source whose style-spec type has no public class in this version and no known layer
 * kind, so no built-in layer composable accepts it.
 */
internal class UnmodeledSource(id: String, private val json: JsonObject) : Source(id) {
  override fun toJson(): JsonObject = json
}

/** A base-style raster source with no public class in this version, such as video or canvas. */
internal class UnmodeledRasterSource(id: String, private val json: JsonObject) : RasterSource(id) {
  override fun toJson(): JsonObject = json
}

/**
 * Keeps [VectorSource] open: callers' `when` needs an `else` branch. Every vector source type in
 * the style spec has a public class, so the library never creates it.
 */
internal class UnmodeledVectorSource(id: String, private val json: JsonObject) : VectorSource(id) {
  override fun toJson(): JsonObject = json
}

/**
 * Get the source with the given [id] from the base style of the current loaded style, or null when
 * the base style has no such source.
 *
 * A source whose style-spec type has no public class in this version is still returned: request it
 * as a [Source], or as a [RasterSource] for a video or canvas source.
 *
 * @throws IllegalStateException if the source is not a [T].
 */
@Composable
public inline fun <reified T : Source> getBaseSource(id: String): T? {
  val source = baseSourceOrNull(id) ?: return null
  check(source is T) {
    "Base source '$id' is a ${source::class.simpleName}, not a ${T::class.simpleName}"
  }
  return source
}

@PublishedApi
@Composable
internal fun baseSourceOrNull(id: String): Source? {
  val node = LocalStyleNode.current
  return remember(node, id) { node.getBaseSource(id) }
}

@Composable
internal fun <T : Source> rememberUserSource(factory: (String) -> T): T {
  val node = LocalStyleNode.current
  val id = remember(node) { node.nextSourceId() }
  // Build a fresh description. No committed object is mutated by speculative composition.
  return remember(node, factory) { factory(id) }
}
