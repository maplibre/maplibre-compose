package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

/** A map data source of tiled vector data. */
public class VectorSource : Source {

  private val json: JsonObject

  /**
   * @param id Unique identifier for this source
   * @param uri URI pointing to a JSON file that conforms to the
   *   [TileJSON specification](https://github.com/mapbox/tilejson-spec/)
   */
  public constructor(id: String, uri: String) : super(id) {
    json = buildJsonObject {
      put("type", "vector")
      put("url", uri)
    }
  }

  /**
   * @param id Unique identifier for this source
   * @param tiles List of URIs pointing to tile images
   * @param options see [TileSetOptions]
   */
  public constructor(id: String, tiles: List<String>, options: TileSetOptions) : super(id) {
    json = buildJsonObject {
      put("type", "vector")
      putJsonArray("tiles") { tiles.forEach { add(it) } }
      putTileSetOptions(options)
    }
  }

  override fun toJson(): JsonObject = json

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
  ): List<Feature<Geometry, JsonObject?>> =
    binding.querySourceFeatures(id, sourceLayerIds, predicate.toFilterJson())

  /**
   * Merges [state] into the runtime state of the feature identified by [featureId] in
   * [sourceLayerId].
   *
   * Style expressions read these values with
   * [feature.state][org.maplibre.compose.expressions.dsl.Feature.state]. Keys already on the
   * feature and absent from [state] stay as they are. [featureId] is matched as text: a feature
   * `id` of `7` is `"7"`.
   */
  public fun setFeatureState(sourceLayerId: String, featureId: String, state: JsonObject) {
    binding.setFeatureState(id, sourceLayerId, featureId, state)
  }

  /**
   * The feature's current runtime state, or an empty object when it has none or the source is not
   * on a live map.
   */
  public fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject =
    binding.featureState(id, sourceLayerId, featureId)

  /**
   * Removes [stateKey] from the feature identified by [featureId] in [sourceLayerId], or every key
   * when [stateKey] is `null`.
   */
  public fun removeFeatureState(
    sourceLayerId: String,
    featureId: String,
    stateKey: String? = null,
  ) {
    binding.removeFeatureState(id, sourceLayerId, featureId, stateKey)
  }

  /** Removes runtime state from every feature in [sourceLayerId]. */
  public fun resetFeatureStates(sourceLayerId: String) {
    binding.resetFeatureStates(id, sourceLayerId)
  }
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
