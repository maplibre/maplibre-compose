@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.map

/** [JumpToOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/JumpToOptions/) */
public sealed external interface JumpToOptions : CameraOptions {
  public var padding: PaddingOptions?
}
