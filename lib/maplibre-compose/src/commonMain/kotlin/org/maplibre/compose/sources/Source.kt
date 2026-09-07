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
 * rather than this value. Layer composables accept a source kind, not this root type: feature
 * layers take a [FeatureSource], raster layers take a [RasterLayerSource], and hillshade and
 * color-relief layers take a [RasterDemSource].
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
 * A source that fill, line, symbol, circle, heatmap, and fill-extrusion layers can draw: vector
 * tiles, GeoJSON, or application-supplied vector data.
 */
public sealed class FeatureSource(id: String) : Source(id)

/**
 * A source that a raster layer can draw: tiled raster pictures, a positioned image, or a video
 * source from a style.
 */
public sealed class RasterLayerSource(id: String) : Source(id)

/**
 * Get the source with the given [id] from the base style of the current loaded style.
 *
 * The type argument selects the reconstructed source class. A vector tile source in the style is a
 * [VectorSource]; a GeoJSON source is a [GeoJsonSource]. The function returns null when the source
 * is missing or is a different class.
 */
@Composable
public inline fun <reified T : Source> getBaseSource(id: String): T? = baseSourceOrNull(id) as? T

@PublishedApi
@Composable
internal fun baseSourceOrNull(id: String): Source? {
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
