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
 * operations without a source layer. Each write is a [MapState][org.maplibre.compose.map.MapState]
 * command.
 */
public sealed interface VectorFeatureSource {

  /**
   * Returns the features in the given [sourceLayerIds], or an empty list when [sourceLayerIds] is
   * empty or no feature matches.
   *
   * @param predicate Keeps only the features for which this expression is true.
   */
  public fun querySourceFeatures(
    sourceLayerIds: Set<String>,
    predicate: Expression<BooleanValue> = const(true),
  ): List<Feature<Geometry, JsonObject?>> =
    source.map?.querySourceFeatures(source.id, sourceLayerIds, predicate.toFilterJson())
      ?: emptyList()

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
    source.map?.setFeatureState(source.id, sourceLayerId, featureId, state)
  }

  /**
   * The feature's current runtime state, or an empty object when it has none or the source is not
   * on a live map.
   */
  public fun getFeatureState(sourceLayerId: String, featureId: String): JsonObject =
    source.map?.featureState(source.id, sourceLayerId, featureId) ?: JsonObject(emptyMap())

  /**
   * Removes [stateKey] from the feature identified by [featureId] in [sourceLayerId], or every key
   * when [stateKey] is `null`.
   */
  public fun removeFeatureState(
    sourceLayerId: String,
    featureId: String,
    stateKey: String? = null,
  ) {
    source.map?.removeFeatureState(source.id, sourceLayerId, featureId, stateKey)
  }

  /** Removes runtime state from every feature in [sourceLayerId]. */
  public fun resetFeatureStates(sourceLayerId: String) {
    source.map?.resetFeatureStates(source.id, sourceLayerId)
  }
}

/** Every implementer is a [Source]; the default bodies reach its map through this cast. */
private val VectorFeatureSource.source: Source
  get() = this as Source
