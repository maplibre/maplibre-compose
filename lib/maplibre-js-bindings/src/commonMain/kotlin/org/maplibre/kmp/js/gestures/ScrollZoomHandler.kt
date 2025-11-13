@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.gestures

/** [ScrollZoomHandler](https://maplibre.org/maplibre-gl-js/docs/API/classes/ScrollZoomHandler/) */
public external class ScrollZoomHandler private constructor() {
  public fun disable()

  public fun enable()

  public fun isEnabled(): Boolean

  public fun isActive(): Boolean

  public fun setWheelZoomRate(wheelZoomRate: Double)

  public fun setZoomRate(zoomRate: Double)
}
