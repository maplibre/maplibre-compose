@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.controls

import org.maplibre.kmp.js.browserapi.PositionOptions
import org.maplibre.kmp.js.map.FitBoundsOptions

/**
 * [GeolocateControlOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/GeolocateControlOptions/)
 */
public external interface GeolocateControlOptions {
  public var fitBoundsOptions: FitBoundsOptions?
  public var positionOptions: PositionOptions?
  public var showAccuracyCircle: Boolean?
  public var showUserLocation: Boolean?
  public var trackUserLocation: Boolean?
}
