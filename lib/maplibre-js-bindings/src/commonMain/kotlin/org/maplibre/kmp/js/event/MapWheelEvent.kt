@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.event

import org.maplibre.kmp.js.map.Map
import org.w3c.dom.events.WheelEvent

/** [MapWheelEvent](https://maplibre.org/maplibre-gl-js/docs/API/classes/MapWheelEvent/) */
public external class MapWheelEvent private constructor() : MapLibreEvent<WheelEvent> {
  public val defaultPrevented: Boolean
  override val originalEvent: WheelEvent
  override val target: Map
  override val type: String

  public fun preventDefault()
}
