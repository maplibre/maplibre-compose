@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.gestures

/**
 * [DoubleClickZoomHandler](https://maplibre.org/maplibre-gl-js/docs/API/classes/DoubleClickZoomHandler/)
 */
public external class DoubleClickZoomHandler private constructor() {
  public fun disable()

  public fun enable()

  public fun isEnabled(): Boolean

  public fun isActive(): Boolean
}
