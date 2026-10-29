package org.maplibre.compose.sources

import org.maplibre.nativeffi.FeatureStateSelector

internal fun featureStateSelector(
  sourceId: String,
  sourceLayerId: String? = null,
  featureId: String? = null,
  stateKey: String? = null,
) =
  FeatureStateSelector(
    sourceId = sourceId,
    sourceLayerId = sourceLayerId.orEmpty(),
    featureId = featureId,
    stateKey = stateKey.orEmpty(),
  )
