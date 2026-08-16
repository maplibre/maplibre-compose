package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

/**
 * A map data source of tiled vector data.
 *
 * Feature-state mutation is available on desktop, Android, and the browser. On iOS the MapLibre SDK
 * keeps that API on the map view, which this source has no path to, so the methods throw
 * [UnsupportedOperationException].
 */
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

  /**
   * Merges [state] into the runtime state of the feature identified by [featureId] in
   * [sourceLayerId].
   *
   * Style expressions read these values with
   * [feature.state][org.maplibre.compose.expressions.dsl.Feature.state]. Keys already on the
   * feature and absent from [state] stay as they are. [featureId] is matched as text: a feature
   * `id` of `7` is `"7"`.
   *
   * A call before the first frame is ignored on desktop, because feature state belongs to the
   * render session.
   */
  public fun setFeatureState(sourceLayerId: String, featureId: String, state: JsonObject)

  /**
   * The feature's current runtime state, or an empty object when it has none or the source is not
   * on a live map.
   *
   * On desktop, [removeFeatureState] and [resetFeatureStates] take effect on the next frame, so a
   * read in the same frame still sees the previous state.
   */
  public fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject

  /**
   * Removes [stateKey] from the feature identified by [featureId] in [sourceLayerId], or every key
   * when [stateKey] is `null`.
   */
  public fun removeFeatureState(sourceLayerId: String, featureId: String, stateKey: String? = null)

  /** Removes runtime state from every feature in [sourceLayerId]. */
  public fun resetFeatureStates(sourceLayerId: String)
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
