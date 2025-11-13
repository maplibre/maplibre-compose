@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.event

import org.maplibre.kmp.js.map.Map

/** [MapLibreEvent](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/MapLibreEvent/) */
public external interface MapLibreEvent<T> : Event {
  public val originalEvent: T
  public val target: Map
  public val type: String
}
