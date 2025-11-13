@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.controls

import org.maplibre.kmp.js.map.Map
import org.w3c.dom.HTMLElement

/**
 * [AttributionControl](https://maplibre.org/maplibre-gl-js/docs/API/classes/AttributionControl/)
 */
public external class AttributionControl
public constructor(options: AttributionControlOptions = definedExternally) : IControl {
  override fun onAdd(map: Map): HTMLElement

  override fun onRemove(map: Map)
}
