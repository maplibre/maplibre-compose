@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.controls

import org.maplibre.kmp.js.map.Map
import org.maplibre.kmp.js.stylespec.TerrainSpecification
import org.w3c.dom.HTMLElement

/** [TerrainControl](https://maplibre.org/maplibre-gl-js/docs/API/classes/TerrainControl/) */
public external class TerrainControl
public constructor(options: TerrainSpecification = definedExternally) : IControl {
  override fun onAdd(map: Map): HTMLElement

  override fun onRemove(map: Map)
}
