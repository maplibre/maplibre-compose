package org.maplibre.kmp.js.controls

import org.maplibre.kmp.js.browserapi.PositionOptions
import org.maplibre.kmp.js.map.FitBoundsOptions
import org.maplibre.kmp.js.util.jso
import org.w3c.dom.HTMLElement

public fun AttributionControlOptions(
  compact: Boolean? = null,
  customAttribution: String? = null,
): AttributionControlOptions = jso {
  compact?.let { this.compact = it }
  customAttribution?.let { this.customAttribution = it }
}

public fun FullscreenControlOptions(container: HTMLElement? = null): FullscreenControlOptions =
  jso {
    container?.let { this.container = it }
  }

public fun GeolocateControlOptions(
  fitBoundsOptions: FitBoundsOptions? = null,
  positionOptions: PositionOptions? = null,
  showAccuracyCircle: Boolean? = null,
  showUserLocation: Boolean? = null,
  trackUserLocation: Boolean? = null,
): GeolocateControlOptions = jso {
  fitBoundsOptions?.let { this.fitBoundsOptions = it }
  positionOptions?.let { this.positionOptions = it }
  showAccuracyCircle?.let { this.showAccuracyCircle = it }
  showUserLocation?.let { this.showUserLocation = it }
  trackUserLocation?.let { this.trackUserLocation = it }
}

public fun LogoControlOptions(compact: Boolean? = null): LogoControlOptions = jso {
  compact?.let { this.compact = it }
}

public fun NavigationControlOptions(
  showCompass: Boolean? = null,
  showZoom: Boolean? = null,
  visualizePitch: Boolean? = null,
): NavigationControlOptions = jso {
  showCompass?.let { this.showCompass = it }
  showZoom?.let { this.showZoom = it }
  visualizePitch?.let { this.visualizePitch = it }
}

public fun ScaleControlOptions(
  maxWidth: Double? = null,
  unit: String? = null,
): ScaleControlOptions = jso {
  maxWidth?.let { this.maxWidth = it }
  unit?.let { this.unit = it }
}
