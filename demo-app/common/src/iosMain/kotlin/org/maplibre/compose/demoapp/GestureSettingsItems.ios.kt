package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.design.SwitchRow

@Composable
actual fun GestureSettingsItems(settings: DemoSettings) {
  val options = settings.gestureOptions
  SwitchRow("Scroll", options.isScrollEnabled) {
    settings.gestureOptions = options.copy(isScrollEnabled = it)
  }
  SwitchRow("Zoom", options.isZoomEnabled) {
    settings.gestureOptions = options.copy(isZoomEnabled = it)
  }
  SwitchRow("Rotate", options.isRotateEnabled) {
    settings.gestureOptions = options.copy(isRotateEnabled = it)
  }
  SwitchRow("Tilt", options.isTiltEnabled) {
    settings.gestureOptions = options.copy(isTiltEnabled = it)
  }
  SwitchRow("Haptic feedback", options.isHapticFeedbackEnabled) {
    settings.gestureOptions = options.copy(isHapticFeedbackEnabled = it)
  }
}
