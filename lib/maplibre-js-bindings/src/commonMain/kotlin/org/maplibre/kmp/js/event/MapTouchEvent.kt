@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.event

import org.maplibre.kmp.js.geometry.LngLat
import org.maplibre.kmp.js.geometry.Point
import org.maplibre.kmp.js.map.Map
import org.w3c.dom.TouchEvent

/** [MapTouchEvent](https://maplibre.org/maplibre-gl-js/docs/API/classes/MapTouchEvent/) */
public external class MapTouchEvent private constructor() : MapLibreEvent<TouchEvent> {
  public val defaultPrevented: Boolean
  public val lngLat: LngLat
  public val lngLats: Array<LngLat>
  override val originalEvent: TouchEvent
  public val point: Point
  public val points: Array<Point>
  override val target: Map
  override val type: String

  public fun preventDefault()
}
