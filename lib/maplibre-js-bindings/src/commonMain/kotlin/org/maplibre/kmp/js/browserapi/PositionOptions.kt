@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.browserapi

/**
 * [PositionOptions](https://developer.mozilla.org/en-US/docs/Web/API/Geolocation/getCurrentPosition)
 */
public sealed external interface PositionOptions {
  public var enableHighAccuracy: Boolean?
  public var timeout: Long?
  public var maximumAge: Long?
}
