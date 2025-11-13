@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.controls

import org.maplibre.kmp.js.map.Map
import org.w3c.dom.HTMLElement

/** [GeolocateControl](https://maplibre.org/maplibre-gl-js/docs/API/classes/GeolocateControl/) */
public external class GeolocateControl
public constructor(options: GeolocateControlOptions = definedExternally) : IControl {
  override fun onAdd(map: Map): HTMLElement

  override fun onRemove(map: Map)
}
