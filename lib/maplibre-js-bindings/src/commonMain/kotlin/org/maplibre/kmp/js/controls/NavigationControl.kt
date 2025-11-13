@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.controls

import org.maplibre.kmp.js.map.Map
import org.w3c.dom.HTMLElement

/** [NavigationControl](https://maplibre.org/maplibre-gl-js/docs/API/classes/NavigationControl/) */
public external class NavigationControl
public constructor(options: NavigationControlOptions = definedExternally) : IControl {
  override fun onAdd(map: Map): HTMLElement

  override fun onRemove(map: Map)
}
