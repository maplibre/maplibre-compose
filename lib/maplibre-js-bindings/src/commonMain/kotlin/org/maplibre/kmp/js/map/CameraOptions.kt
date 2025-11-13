@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.map

import org.maplibre.kmp.js.geometry.LngLat

/** [CameraOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/CameraOptions/) */
public sealed external interface CameraOptions : CenterZoomBearing {
  public var around: LngLat?
  public var pitch: Double?
}
