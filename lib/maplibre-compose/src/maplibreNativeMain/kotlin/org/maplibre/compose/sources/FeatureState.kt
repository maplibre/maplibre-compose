package org.maplibre.compose.sources

import org.maplibre.nativeffi.generated.FeatureStateSelector

internal fun featureStateSelector(
  sourceId: String,
  sourceLayerId: String? = null,
  featureId: String? = null,
  stateKey: String? = null,
) =
  FeatureStateSelector(
    sourceId = sourceId,
    sourceLayerId = sourceLayerId,
    featureId = featureId,
    stateKey = stateKey,
  )
