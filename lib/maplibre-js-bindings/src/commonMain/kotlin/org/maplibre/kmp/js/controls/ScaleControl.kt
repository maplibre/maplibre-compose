@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.controls

import org.maplibre.kmp.js.map.Map
import org.w3c.dom.HTMLElement

/** [ScaleControl](https://maplibre.org/maplibre-gl-js/docs/API/classes/ScaleControl/) */
public external class ScaleControl
public constructor(options: ScaleControlOptions = definedExternally) : IControl {
  override fun onAdd(map: Map): HTMLElement

  override fun onRemove(map: Map)
}
