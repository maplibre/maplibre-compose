package org.maplibre.compose.sources

import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

/**
 * A source whose features live in named source layers: [VectorSource] and [CustomVectorSource].
 *
 * Every operation names the source layer that holds the feature.
 * [GeoJsonSource][org.maplibre.compose.sources.GeoJsonSource] offers the same feature-state
 * operations without a source layer.
 */
public sealed interface VectorFeatureSource {

  /**
   * Returns a list of features from the source, limited to source layers with the given
   * [sourceLayerIds] and filtered by the given [predicate].
   *
   * @param sourceLayerIds A set of source layer IDs to query features from.
   * @param predicate An expression used to filter the features. If not specified, all features from
   *   the source are returned.
   * @return A list of features that match the query, or an empty list if the [sourceLayerIds] is
   *   empty or no features are found.
   */
  public fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> =
    source.binding.querySourceFeatures(source.id, sourceLayerIds, predicate.toFilterJson())

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
    source.binding.setFeatureState(source.id, sourceLayerId, featureId, state)
  }

  /**
   * The feature's current runtime state, or an empty object when it has none or the source is not
   * on a live map.
   */
  public fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject =
    source.binding.featureState(source.id, sourceLayerId, featureId)

  /**
   * Removes [stateKey] from the feature identified by [featureId] in [sourceLayerId], or every key
   * when [stateKey] is `null`.
   */
  public fun removeFeatureState(
    sourceLayerId: String,
    featureId: String,
    stateKey: String? = null,
  ) {
    source.binding.removeFeatureState(source.id, sourceLayerId, featureId, stateKey)
  }

  /** Removes runtime state from every feature in [sourceLayerId]. */
  public fun resetFeatureStates(sourceLayerId: String) {
    source.binding.resetFeatureStates(source.id, sourceLayerId)
  }
}

/** Every implementer is a [Source]; the default bodies reach its binding through this cast. */
private val VectorFeatureSource.source: Source
  get() = this as Source
