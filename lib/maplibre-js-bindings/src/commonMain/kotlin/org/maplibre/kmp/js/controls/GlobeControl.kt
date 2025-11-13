@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.controls

import org.maplibre.kmp.js.map.Map
import org.w3c.dom.HTMLElement

/** [GlobeControl](https://maplibre.org/maplibre-gl-js/docs/API/classes/GlobeControl/) */
public external class GlobeControl public constructor() : IControl {
  override fun onAdd(map: Map): HTMLElement

  override fun onRemove(map: Map)
}
