@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.event

import org.maplibre.kmp.js.geometry.LngLat
import org.maplibre.kmp.js.geometry.Point
import org.maplibre.kmp.js.map.Map
import org.w3c.dom.events.MouseEvent

/** [MapMouseEvent](https://maplibre.org/maplibre-gl-js/docs/API/classes/MapMouseEvent/) */
public external class MapMouseEvent private constructor() : MapLibreEvent<MouseEvent> {
  public val defaultPrevented: Boolean
  public val lngLat: LngLat
  override val originalEvent: MouseEvent
  public val point: Point
  override val target: Map
  override val type: String

  public fun preventDefault()
}
