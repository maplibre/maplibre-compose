package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.design.SwitchRow

/** Gesture option toggles, as settings list items. */
@Composable
fun GestureSettingsItems(settings: DemoSettings) {
  val options = settings.gestureOptions
  SwitchRow("Drag pan", options.isDragPanEnabled) {
    settings.gestureOptions = options.copy(isDragPanEnabled = it)
  }
  SwitchRow("Drag rotate and tilt", options.isDragRotateTiltEnabled) {
    settings.gestureOptions = options.copy(isDragRotateTiltEnabled = it)
  }
  SwitchRow("Pinch zoom", options.isPinchZoomEnabled) {
    settings.gestureOptions = options.copy(isPinchZoomEnabled = it)
  }
  SwitchRow("Two-finger rotate", options.isTwoFingerRotateEnabled) {
    settings.gestureOptions = options.copy(isTwoFingerRotateEnabled = it)
  }
  SwitchRow("Two-finger tilt", options.isTwoFingerTiltEnabled) {
    settings.gestureOptions = options.copy(isTwoFingerTiltEnabled = it)
  }
  SwitchRow("Two-finger tap zoom", options.isTwoFingerTapZoomEnabled) {
    settings.gestureOptions = options.copy(isTwoFingerTapZoomEnabled = it)
  }
  SwitchRow("Scroll zoom", options.isScrollZoomEnabled) {
    settings.gestureOptions = options.copy(isScrollZoomEnabled = it)
  }
  SwitchRow("Double-click zoom", options.isDoubleClickZoomEnabled) {
    settings.gestureOptions = options.copy(isDoubleClickZoomEnabled = it)
  }
  SwitchRow("Quick zoom", options.isQuickZoomEnabled) {
    settings.gestureOptions = options.copy(isQuickZoomEnabled = it)
  }
  SwitchRow("Fling", options.isFlingEnabled) {
    settings.gestureOptions = options.copy(isFlingEnabled = it)
  }
  SwitchRow("Pinch zoom velocity", options.isPinchZoomVelocityEnabled) {
    settings.gestureOptions = options.copy(isPinchZoomVelocityEnabled = it)
  }
  SwitchRow("Rotate velocity", options.isRotateVelocityEnabled) {
    settings.gestureOptions = options.copy(isRotateVelocityEnabled = it)
  }
  SwitchRow("Keyboard pan", options.isKeyboardPanEnabled) {
    settings.gestureOptions = options.copy(isKeyboardPanEnabled = it)
  }
  SwitchRow("Keyboard zoom", options.isKeyboardZoomEnabled) {
    settings.gestureOptions = options.copy(isKeyboardZoomEnabled = it)
  }
  SwitchRow("Keyboard rotate and tilt", options.isKeyboardRotateTiltEnabled) {
    settings.gestureOptions = options.copy(isKeyboardRotateTiltEnabled = it)
  }
}
