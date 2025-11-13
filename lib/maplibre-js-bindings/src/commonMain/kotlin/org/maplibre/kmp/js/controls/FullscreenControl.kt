@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.controls

import org.maplibre.kmp.js.map.Map
import org.w3c.dom.HTMLElement

/** [FullscreenControl](https://maplibre.org/maplibre-gl-js/docs/API/classes/FullscreenControl/) */
public external class FullscreenControl
public constructor(options: FullscreenControlOptions = definedExternally) : IControl {
  override fun onAdd(map: Map): HTMLElement

  override fun onRemove(map: Map)
}
