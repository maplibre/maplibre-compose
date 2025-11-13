@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.geometry

/** [LngLat](https://maplibre.org/maplibre-gl-js/docs/API/classes/LngLat/) */
public external class LngLat(public val lng: Double, public val lat: Double) {
  public fun distanceTo(lngLat: LngLat): Double

  public fun toArray(): DoubleArray

  public fun wrap(): LngLat
}
