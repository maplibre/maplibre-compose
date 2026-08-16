package org.maplibre.compose.sources

// TODO: call MLNShapeSource / MLNVectorTileSource once
// https://github.com/maplibre/maplibre-native/pull/4420 ships.
internal fun featureStateUnavailable(): Nothing =
  throw UnsupportedOperationException("Feature state updates are not available on iOS.")
