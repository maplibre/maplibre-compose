package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

/** A map data source of tiled vector data. */
public expect class VectorSource : Source {

  /**
   * @param id Unique identifier for this source
   * @param uri URI pointing to a JSON file that conforms to the
   *   [TileJSON specification](https://github.com/mapbox/tilejson-spec/)
   */
  public constructor(id: String, uri: String)

  /**
   * @param id Unique identifier for this source
   * @param tiles List of URIs pointing to tile images
   * @param options see [TileSetOptions]
   */
  public constructor(id: String, tiles: List<String>, options: TileSetOptions)

  /**
   * Returns a list of features from the vector source, limited to source layers with the given
   * [sourceLayerIds] and filtered by the given [predicate].
   *
   * @param sourceLayerIds A set of source layer IDs to query features from.
   * @param predicate An expression used to filter the features. If not specified, all features from
   *   the vector source are returned.
   * @return A list of features that match the query, or an empty list if the [sourceLayerIds] is
   *   empty or no features are found.
   */
  public fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>>
}

/** Remember a new [VectorSource] from the given [uri]. */
@Composable
public fun rememberVectorSource(uri: String): VectorSource =
  key(uri) { rememberUserSource(factory = { VectorSource(id = it, uri = uri) }, update = {}) }

@Composable
public fun rememberVectorSource(
  tiles: List<String>,
  options: TileSetOptions = TileSetOptions(),
): VectorSource =
  key(tiles, options) {
    rememberUserSource(
      factory = { VectorSource(id = it, tiles = tiles, options = options) },
      update = {},
    )
  }
