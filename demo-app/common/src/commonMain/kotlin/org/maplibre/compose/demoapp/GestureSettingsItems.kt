package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.maplibre.compose.demoapp.design.SliderRow
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.map.MapGestures

@Stable
class DemoGestureSettings {
  var dragPan by mutableStateOf(true)
  var dragRotateTilt by mutableStateOf(true)
  var pinchZoom by mutableStateOf(true)
  var twoFingerRotate by mutableStateOf(true)
  var twoFingerTilt by mutableStateOf(true)
  var twoFingerTap by mutableStateOf(true)
  var scrollPan by mutableStateOf(true)
  var scrollZoom by mutableStateOf(true)
  var rotaryZoom by mutableStateOf(true)
  var doubleTap by mutableStateOf(true)
  var quickZoom by mutableStateOf(true)
  var boxZoom by mutableStateOf(true)
  var fling by mutableStateOf(true)
  var pinchVelocity by mutableStateOf(true)
  var rotateVelocity by mutableStateOf(true)
  var keyboardPan by mutableStateOf(true)
  var keyboardZoom by mutableStateOf(true)
  var keyboardRotateTilt by mutableStateOf(true)

  var panSlop by mutableStateOf(4f)
  var mousePanSlop by mutableStateOf(3f)
  var pinchSlop by mutableStateOf(7f)
  var rotateAngle by mutableStateOf(3f)
  var tiltSlop by mutableStateOf(16f)

  val hasKeyboardGesture
    get() = keyboardPan || keyboardZoom || keyboardRotateTilt

  val gestures: MapGestures
    get() = MapGestures {
      dragPan {
        enabled = this@DemoGestureSettings.dragPan
        startSlop = panSlop.dp
        mouseStartSlop = mousePanSlop.dp
      }
      dragRotateTilt { enabled = this@DemoGestureSettings.dragRotateTilt }
      pinchZoom {
        enabled = this@DemoGestureSettings.pinchZoom
        startSpanSlop = pinchSlop.dp
      }
      twoFingerRotate {
        enabled = this@DemoGestureSettings.twoFingerRotate
        startAngle = rotateAngle.toDouble()
      }
      twoFingerTilt {
        enabled = this@DemoGestureSettings.twoFingerTilt
        startSlop = tiltSlop.dp
      }
      twoFingerTap { enabled = this@DemoGestureSettings.twoFingerTap }
      scrollPan { enabled = this@DemoGestureSettings.scrollPan }
      scrollZoom { enabled = this@DemoGestureSettings.scrollZoom }
      doubleTap { enabled = this@DemoGestureSettings.doubleTap }
      quickZoom { enabled = this@DemoGestureSettings.quickZoom }
      boxZoom { enabled = this@DemoGestureSettings.boxZoom }
      ctrlScrollZoom { enabled = this@DemoGestureSettings.scrollZoom }
      dragPan { if (!fling) continuation = null }
      pinchZoom { if (!pinchVelocity) continuation = null }
      twoFingerRotate { if (!rotateVelocity) continuation = null }
      keys {
        rotaryZoom { enabled = this@DemoGestureSettings.rotaryZoom }
        if (!keyboardPan) clearPan()
        if (!keyboardZoom) clearZoom()
        if (!keyboardRotateTilt) {
          clearRotate()
          clearTilt()
        }
      }
    }
}

/** Input controls shared by the demo screens. */
@Composable
fun GestureSettingsItems(settings: DemoSettings) {
  val gestures = settings.gestureSettings
  SwitchRow("Drag pan", gestures.dragPan) { gestures.dragPan = it }
  SwitchRow("Drag rotate and tilt", gestures.dragRotateTilt) { gestures.dragRotateTilt = it }
  SwitchRow("Pinch zoom", gestures.pinchZoom) { gestures.pinchZoom = it }
  SwitchRow("Two-finger rotate", gestures.twoFingerRotate) { gestures.twoFingerRotate = it }
  SwitchRow("Two-finger tilt", gestures.twoFingerTilt) { gestures.twoFingerTilt = it }
  SwitchRow("Two-finger tap", gestures.twoFingerTap) { gestures.twoFingerTap = it }
  SwitchRow("Scroll pan", gestures.scrollPan) { gestures.scrollPan = it }
  SwitchRow("Scroll zoom", gestures.scrollZoom) { gestures.scrollZoom = it }
  SwitchRow("Rotary zoom", gestures.rotaryZoom) { gestures.rotaryZoom = it }
  SwitchRow("Double tap", gestures.doubleTap) { gestures.doubleTap = it }
  SwitchRow("Quick zoom", gestures.quickZoom) { gestures.quickZoom = it }
  SwitchRow("Box zoom", gestures.boxZoom) { gestures.boxZoom = it }
  SwitchRow("Fling", gestures.fling) { gestures.fling = it }
  SwitchRow("Pinch zoom velocity", gestures.pinchVelocity) { gestures.pinchVelocity = it }
  SwitchRow("Rotate velocity", gestures.rotateVelocity) { gestures.rotateVelocity = it }
  SwitchRow("Keyboard pan", gestures.keyboardPan) { gestures.keyboardPan = it }
  SwitchRow("Keyboard zoom", gestures.keyboardZoom) { gestures.keyboardZoom = it }
  SwitchRow("Keyboard rotate and tilt", gestures.keyboardRotateTilt) {
    gestures.keyboardRotateTilt = it
  }
  SliderRow("Pan threshold", gestures.panSlop, 0f..24f, { "${it.roundToInt()} dp" }) {
    gestures.panSlop = it
  }
  SliderRow("Mouse pan threshold", gestures.mousePanSlop, 0f..16f, { "${it.roundToInt()} dp" }) {
    gestures.mousePanSlop = it
  }
  SliderRow("Pinch span threshold", gestures.pinchSlop, 0f..32f, { "${it.roundToInt()} dp" }) {
    gestures.pinchSlop = it
  }
  SliderRow("Rotation threshold", gestures.rotateAngle, 0f..15f, { "${it.roundToInt()}°" }) {
    gestures.rotateAngle = it
  }
  SliderRow("Tilt threshold", gestures.tiltSlop, 0f..40f, { "${it.roundToInt()} dp" }) {
    gestures.tiltSlop = it
  }
}
