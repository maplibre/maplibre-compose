package org.maplibre.compose.sources

import org.maplibre.nativeffi.query.FeatureStateSelector

internal fun featureStateSelector(
  sourceId: String,
  sourceLayerId: String? = null,
  featureId: String? = null,
  stateKey: String? = null,
) =
  FeatureStateSelector(sourceId).apply {
    this.sourceLayerId = sourceLayerId
    this.featureId = featureId
    this.stateKey = stateKey
  }
