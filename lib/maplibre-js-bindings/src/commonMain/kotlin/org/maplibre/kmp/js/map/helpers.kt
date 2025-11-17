package org.maplibre.kmp.js.map

import org.maplibre.kmp.js.geometry.LngLat
import org.maplibre.kmp.js.geometry.Point
import org.maplibre.kmp.js.stylespec.Expression
import org.maplibre.kmp.js.util.jso
import org.w3c.dom.HTMLElement

public fun CameraOptions(around: LngLat? = null, pitch: Double? = null): CameraOptions = jso {
  this.around = around
  this.pitch = pitch
}

public fun CenterZoomBearing(bearing: Double?, center: LngLat?, zoom: Double?): CenterZoomBearing =
  jso {
    this.bearing = bearing
    this.center = center
    this.zoom = zoom
  }

public fun EaseToOptions(
  center: LngLat? = null,
  zoom: Double? = null,
  bearing: Double? = null,
  pitch: Double? = null,
  padding: PaddingOptions? = null,
  duration: Double? = null,
  easing: ((Double) -> Double)? = null,
): EaseToOptions = jso {
  center?.let { this.center = it }
  zoom?.let { this.zoom = it }
  bearing?.let { this.bearing = it }
  pitch?.let { this.pitch = it }
  padding?.let { this.padding = it }
  duration?.let { this.duration = it }
  easing?.let { this.easing = it }
}

public fun FitBoundsOptions(
  linear: Boolean? = null,
  maxZoom: Double? = null,
  offset: Point? = null,
  center: LngLat? = null,
  zoom: Double? = null,
  bearing: Double? = null,
  pitch: Double? = null,
  speed: Double? = null,
  curve: Double? = null,
  maxDuration: Double? = null,
  minZoom: Double? = null,
  padding: PaddingOptions? = null,
  screenSpeed: Double? = null,
): FitBoundsOptions = jso {
  linear?.let { this.linear = it }
  maxZoom?.let { this.maxZoom = it }
  offset?.let { this.offset = it }
  center?.let { this.center = it }
  zoom?.let { this.zoom = it }
  bearing?.let { this.bearing = it }
  pitch?.let { this.pitch = it }
  speed?.let { this.speed = it }
  curve?.let { this.curve = it }
  maxDuration?.let { this.maxDuration = it }
  minZoom?.let { this.minZoom = it }
  padding?.let { this.padding = it }
  screenSpeed?.let { this.screenSpeed = it }
}

public fun FlyToOptions(
  center: LngLat? = null,
  zoom: Double? = null,
  bearing: Double? = null,
  pitch: Double? = null,
  speed: Double? = null,
  curve: Double? = null,
  maxDuration: Double? = null,
  minZoom: Double? = null,
  padding: PaddingOptions? = null,
  screenSpeed: Double? = null,
): FlyToOptions = jso {
  center?.let { this.center = it }
  zoom?.let { this.zoom = it }
  bearing?.let { this.bearing = it }
  pitch?.let { this.pitch = it }
  speed?.let { this.speed = it }
  curve?.let { this.curve = it }
  maxDuration?.let { this.maxDuration = it }
  minZoom?.let { this.minZoom = it }
  padding?.let { this.padding = it }
  screenSpeed?.let { this.screenSpeed = it }
}

public fun JumpToOptions(
  center: LngLat? = null,
  zoom: Double? = null,
  bearing: Double? = null,
  pitch: Double? = null,
  padding: PaddingOptions? = null,
): JumpToOptions = jso {
  center?.let { this.center = it }
  zoom?.let { this.zoom = it }
  bearing?.let { this.bearing = it }
  pitch?.let { this.pitch = it }
  padding?.let { this.padding = it }
}

public fun MapOptions(
  container: HTMLElement,
  disableAttributionControl: Boolean = false,
): MapOptions = jso {
  this.container = container
  if (disableAttributionControl) {
    this.attributionControl = false
  }
}

public fun PaddingOptions(
  top: Double? = null,
  bottom: Double? = null,
  left: Double? = null,
  right: Double? = null,
): PaddingOptions = jso {
  top?.let { this.top = it }
  bottom?.let { this.bottom = it }
  left?.let { this.left = it }
  right?.let { this.right = it }
}

public fun QueryRenderedFeaturesOptions(
  availableImages: Array<String>? = null,
  layers: Array<String>? = null,
  filter: Expression? = null,
  validate: Boolean? = null,
): QueryRenderedFeaturesOptions = jso {
  availableImages?.let { this.availableImages = it }
  layers?.let { this.layers = it }
  filter?.let { this.filter = it }
  validate?.let { this.validate = it }
}

public fun CameraForBoundsOptions(
  maxZoom: Double? = null,
  offset: Point? = null,
  padding: PaddingOptions? = null,
  around: LngLat? = null,
  pitch: Double? = null,
  bearing: Double? = null,
  center: LngLat? = null,
  zoom: Double? = null,
): CameraForBoundsOptions = jso {
  maxZoom?.let { this.maxZoom = it }
  offset?.let { this.offset = it }
  padding?.let { this.padding = it }
  around?.let { this.around = it }
  pitch?.let { this.pitch = it }
  bearing?.let { this.bearing = it }
  center?.let { this.center = it }
  zoom?.let { this.zoom = it }
}
