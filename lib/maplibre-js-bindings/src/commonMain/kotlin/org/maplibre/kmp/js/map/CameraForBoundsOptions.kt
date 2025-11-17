package org.maplibre.kmp.js.map

import org.maplibre.kmp.js.geometry.Point

/**
 * [CameraForBoundsOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/CameraForBoundsOptions/)
 */
public sealed external interface CameraForBoundsOptions : CameraOptions {
  public var maxZoom: Double?
  public var offset: Point?
  public var padding: PaddingOptions?
}
