@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.map

import org.w3c.dom.HTMLElement

/** [MapOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/MapOptions/) */
public sealed external interface MapOptions {
  public var container: HTMLElement
  public var attributionControl: dynamic // false | AttributionControlOptions
}
