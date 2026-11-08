package org.maplibre.compose.sources

import org.maplibre.nativeffi.FeatureId
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
    // Native keys feature state by the ID's text, whatever its type.
    featureId = featureId?.let(FeatureId::StringValue),
    stateKey = stateKey.orEmpty(),
  )
