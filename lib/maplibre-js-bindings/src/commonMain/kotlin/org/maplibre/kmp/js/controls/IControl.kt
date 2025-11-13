@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.controls

import org.maplibre.kmp.js.map.Map
import org.w3c.dom.HTMLElement

/** [IControl](https://maplibre.org/maplibre-gl-js/docs/API/interfaces/IControl/) */
public external interface IControl {
  public fun onAdd(map: Map): HTMLElement

  public fun onRemove(map: Map)
}
