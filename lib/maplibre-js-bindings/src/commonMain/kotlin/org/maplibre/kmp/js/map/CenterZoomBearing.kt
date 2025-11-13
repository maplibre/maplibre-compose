@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.map

import org.maplibre.kmp.js.geometry.LngLat

/**
 * [CenterZoomBearing](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/CenterZoomBearing/)
 */
public sealed external interface CenterZoomBearing {
  public var bearing: Double?
  public var center: LngLat?
  public var zoom: Double?
}
