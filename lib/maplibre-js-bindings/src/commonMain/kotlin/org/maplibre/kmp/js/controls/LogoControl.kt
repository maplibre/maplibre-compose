@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.controls

import org.maplibre.kmp.js.map.Map
import org.w3c.dom.HTMLElement

/** [LogoControl](https://maplibre.org/maplibre-gl-js/docs/API/classes/LogoControl/) */
public external class LogoControl
public constructor(options: LogoControlOptions = definedExternally) : IControl {
  override fun onAdd(map: Map): HTMLElement

  override fun onRemove(map: Map)
}
