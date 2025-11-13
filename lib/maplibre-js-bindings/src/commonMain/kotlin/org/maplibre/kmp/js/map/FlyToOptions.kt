@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.map

/** [FlyToOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/FlyToOptions/) */
public sealed external interface FlyToOptions : CameraOptions {
  public var curve: Double?
  public var maxDuration: Double?
  public var minZoom: Double?
  public var padding: PaddingOptions?
  public var speed: Double?
  public var screenSpeed: Double?
}
