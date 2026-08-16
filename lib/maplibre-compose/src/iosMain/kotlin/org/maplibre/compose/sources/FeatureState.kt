package org.maplibre.compose.sources

internal fun featureStateUnavailable(): Nothing =
  throw UnsupportedOperationException(
    "Feature state updates are not available on iOS. The MapLibre iOS SDK keeps that API on the " +
      "map view, which this source has no path to."
  )
