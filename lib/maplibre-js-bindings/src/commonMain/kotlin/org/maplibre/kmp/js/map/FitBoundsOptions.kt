@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.map

import org.maplibre.kmp.js.geometry.Point

/**
 * [FitBoundsOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/FitBoundsOptions/)
 */
public sealed external interface FitBoundsOptions : FlyToOptions {
  public var linear: Boolean?
  public var maxZoom: Double?
  public var offset: Point?
}
