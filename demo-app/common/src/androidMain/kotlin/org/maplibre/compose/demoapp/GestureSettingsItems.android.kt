package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.design.SwitchRow

@Composable
actual fun GestureSettingsItems(settings: DemoSettings) {
  val options = settings.gestureOptions
  SwitchRow("Drag to pan", options.isScrollEnabled) {
    settings.gestureOptions = options.copy(isScrollEnabled = it)
  }
  SwitchRow("Pinch to zoom", options.isZoomEnabled) {
    settings.gestureOptions = options.copy(isZoomEnabled = it)
  }
  SwitchRow("Double-tap to zoom", options.isDoubleTapEnabled) {
    settings.gestureOptions = options.copy(isDoubleTapEnabled = it)
  }
  SwitchRow("Quick zoom", options.isQuickZoomEnabled) {
    settings.gestureOptions = options.copy(isQuickZoomEnabled = it)
  }
  SwitchRow("Two-finger rotate", options.isRotateEnabled) {
    settings.gestureOptions = options.copy(isRotateEnabled = it)
  }
  SwitchRow("Two-finger tilt", options.isTiltEnabled) {
    settings.gestureOptions = options.copy(isTiltEnabled = it)
  }
}
