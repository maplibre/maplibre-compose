package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.maplibre.compose.demoapp.design.SliderRow
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.map.KeyModifier
import org.maplibre.compose.map.MapInteractions
import org.maplibre.compose.map.ModifierMatch.Containing
import org.maplibre.compose.map.ModifierMatch.Exactly
import org.maplibre.compose.map.PointerButton

@Stable
class DemoGestureSettings {
  var dragPan by mutableStateOf(true)
  var dragRotateTilt by mutableStateOf(true)
  var transformPan by mutableStateOf(true)
  var pinchZoom by mutableStateOf(true)
  var twoFingerRotate by mutableStateOf(true)
  var twoFingerTilt by mutableStateOf(true)
  var twoFingerTap by mutableStateOf(true)
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

  val interactions: MapInteractions
    get() = MapInteractions {
      camera {
        pan { momentum { enabled = fling } }
        zoom { momentum { enabled = pinchVelocity } }
        rotate { momentum { enabled = rotateVelocity } }
      }
      bindings {
        drag {
          pan {
            startSlop = panSlop.dp
            mouseStartSlop = mousePanSlop.dp
          }
          mappings {
            if (dragRotateTilt) {
              on(pointerTypes = setOf(PointerType.Mouse), button = PointerButton.Secondary) {
                rotateTilt()
              }
              on(
                pointerTypes = setOf(PointerType.Mouse),
                button = PointerButton.Primary,
                modifiers = Containing(KeyModifier.Ctrl),
              ) {
                rotateTilt()
              }
            }
            if (boxZoom) {
              on(
                pointerTypes = setOf(PointerType.Mouse),
                button = PointerButton.Primary,
                modifiers = Containing(KeyModifier.Shift),
              ) {
                fitBounds()
              }
            }
            if (dragPan) on(button = PointerButton.Primary) { pan() }
          }
        }
        transform {
          pan {
            enabled = transformPan
            startSlop = panSlop.dp
          }
          zoom {
            enabled = pinchZoom
            startSpanSlop = pinchSlop.dp
          }
          rotate {
            enabled = twoFingerRotate
            startAngle = rotateAngle.toDouble()
          }
          tilt {
            enabled = twoFingerTilt
            startSlop = tiltSlop.dp
          }
        }
        twoFingerTap { enabled = this@DemoGestureSettings.twoFingerTap }
        scroll { enabled = scrollZoom }
        doubleTap { enabled = this@DemoGestureSettings.doubleTap }
        tapDrag { enabled = quickZoom }
        rotary { enabled = rotaryZoom }
        keys {
          mappings {
            if (keyboardPan) {
              on(Key.DirectionLeft) { panLeft() }
              on(Key.DirectionRight) { panRight() }
              on(Key.DirectionUp) { panUp() }
              on(Key.DirectionDown) { panDown() }
            }
            if (keyboardRotateTilt) {
              on(Key.DirectionLeft, Exactly(KeyModifier.Shift)) { rotateLeft() }
              on(Key.DirectionRight, Exactly(KeyModifier.Shift)) { rotateRight() }
              on(Key.DirectionUp, Exactly(KeyModifier.Shift)) { tiltUp() }
              on(Key.DirectionDown, Exactly(KeyModifier.Shift)) { tiltDown() }
            }
            if (keyboardZoom) {
              on(Key.Plus) { zoomIn() }
              on(Key.Equals) { zoomIn() }
              on(Key.Plus, Exactly(KeyModifier.Shift)) { zoomIn() }
              on(Key.Equals, Exactly(KeyModifier.Shift)) { zoomIn() }
              on(Key.Minus) { zoomOut() }
            }
            on(Key.Enter) { engage() }
            on(Key.NumPadEnter) { engage() }
            on(Key.DirectionCenter) { engage() }
            on(Key.Escape) { disengage() }
            on(Key.Back) { back() }
          }
        }
      }
    }
}

/** Input controls shared by the demo screens. */
@Composable
fun GestureSettingsItems(settings: DemoSettings) {
  val interactions = settings.gestureSettings
  SwitchRow("Drag pan", interactions.dragPan) { interactions.dragPan = it }
  SwitchRow("Drag rotate and tilt", interactions.dragRotateTilt) {
    interactions.dragRotateTilt = it
  }
  SwitchRow("Two-finger pan", interactions.transformPan) { interactions.transformPan = it }
  SwitchRow("Pinch zoom", interactions.pinchZoom) { interactions.pinchZoom = it }
  SwitchRow("Two-finger rotate", interactions.twoFingerRotate) { interactions.twoFingerRotate = it }
  SwitchRow("Two-finger tilt", interactions.twoFingerTilt) { interactions.twoFingerTilt = it }
  SwitchRow("Two-finger tap", interactions.twoFingerTap) { interactions.twoFingerTap = it }
  SwitchRow("Scroll zoom", interactions.scrollZoom) { interactions.scrollZoom = it }
  SwitchRow("Rotary zoom", interactions.rotaryZoom) { interactions.rotaryZoom = it }
  SwitchRow("Double tap", interactions.doubleTap) { interactions.doubleTap = it }
  SwitchRow("Quick zoom", interactions.quickZoom) { interactions.quickZoom = it }
  SwitchRow("Box zoom", interactions.boxZoom) { interactions.boxZoom = it }
  SwitchRow("Fling", interactions.fling) { interactions.fling = it }
  SwitchRow("Pinch zoom velocity", interactions.pinchVelocity) { interactions.pinchVelocity = it }
  SwitchRow("Rotate velocity", interactions.rotateVelocity) { interactions.rotateVelocity = it }
  SwitchRow("Keyboard pan", interactions.keyboardPan) { interactions.keyboardPan = it }
  SwitchRow("Keyboard zoom", interactions.keyboardZoom) { interactions.keyboardZoom = it }
  SwitchRow("Keyboard rotate and tilt", interactions.keyboardRotateTilt) {
    interactions.keyboardRotateTilt = it
  }
  SliderRow("Pan threshold", interactions.panSlop, 0f..24f, { "${it.roundToInt()} dp" }) {
    interactions.panSlop = it
  }
  SliderRow(
    "Mouse pan threshold",
    interactions.mousePanSlop,
    0f..16f,
    { "${it.roundToInt()} dp" },
  ) {
    interactions.mousePanSlop = it
  }
  SliderRow("Pinch span threshold", interactions.pinchSlop, 0f..32f, { "${it.roundToInt()} dp" }) {
    interactions.pinchSlop = it
  }
  SliderRow("Rotation threshold", interactions.rotateAngle, 0f..15f, { "${it.roundToInt()}°" }) {
    interactions.rotateAngle = it
  }
  SliderRow("Tilt threshold", interactions.tiltSlop, 0f..40f, { "${it.roundToInt()} dp" }) {
    interactions.tiltSlop = it
  }
}
