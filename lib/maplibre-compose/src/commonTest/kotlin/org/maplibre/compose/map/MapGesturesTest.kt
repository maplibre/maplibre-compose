package org.maplibre.compose.map

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.util.ClickResult

class MapGesturesTest {
  @Test
  fun default_builder_edits_standard_and_preserves_priority() {
    assertEquals(MapGestures.Standard, MapGestures {})
    val disabled = MapGestures { dragRotateTilt { enabled = false } }
    val restored = MapGestures(from = disabled) { dragRotateTilt { enabled = true } }
    assertEquals(MapGestures.Standard, restored)
    assertEquals(
      listOf("quickZoom", "dragRotateTilt", "boxZoom", "dragPan"),
      restored.bindings.filter { it.family == GestureFamily.Drag }.map { it.id },
    )
  }

  @Test
  fun custom_ids_are_unique_and_last_declared_has_highest_priority() {
    val gestures = MapGestures {
      drag("first") {
        action = DragAction.Custom
        onEvent {}
      }
      drag("second") {
        action = DragAction.Custom
        onEvent {}
      }
    }
    assertEquals(listOf("second", "first", "quickZoom"), gestures.bindings.take(3).map { it.id })
    assertFailsWith<IllegalArgumentException> { MapGestures(from = gestures) { drag("first") {} } }
    for (binding in MapGestures.Standard.bindings) {
      assertFailsWith<IllegalArgumentException> { MapGestures { drag(binding.id) {} } }
    }
    assertFailsWith<IllegalArgumentException> { MapGestures { drag(" ") {} } }
  }

  @Test
  fun builder_does_not_retain_mutable_inputs_or_leaked_builder_state() {
    val types = mutableSetOf(PointerType.Mouse, PointerType.Touch)
    val filters =
      mutableListOf(PointerFilter(types), PointerFilter(button = PointerButton.Secondary))
    val kinds = mutableSetOf(ScrollKind.Continuous, ScrollKind.Discrete)
    lateinit var retained: PanGestureBuilder
    lateinit var retainedKeys: GestureKeysBuilder
    val gestures = MapGestures {
      dragPan {
        retained = this
        this.filters = filters
      }
      scrollPan { this.kinds = kinds }
      keys { retainedKeys = this }
    }
    val originalKey = gestures.structuralKey
    types.clear()
    filters.clear()
    kinds.clear()
    retained.enabled = false
    retained.startSlop = 60.dp
    retainedKeys.clear()
    assertEquals(2, gestures.binding("dragPan").filters.size)
    assertEquals(
      setOf(PointerType.Mouse, PointerType.Touch),
      gestures.binding("dragPan").filters.first().pointerTypes,
    )
    assertEquals(ScrollKind.entries.toSet(), gestures.binding("scrollPan").settings.scrollKinds)
    assertTrue(gestures.binding("dragPan").enabled)
    assertEquals(4.dp, gestures.binding("dragPan").settings.startSlop)
    assertTrue(gestures.keyBindings.hasCameraBindings)
    assertEquals(originalKey, gestures.structuralKey)
  }

  @Test
  fun filter_assignment_replaces_the_entire_or_list_and_empty_list_is_preserved() {
    val gestures = MapGestures {
      dragRotateTilt { filter = PointerFilter(modifiers = ModifierFilter.Exactly()) }
    }
    assertEquals(
      listOf(PointerFilter(modifiers = ModifierFilter.Exactly())),
      gestures.binding("dragRotateTilt").filters,
    )
    assertEquals(
      emptyList(),
      MapGestures { dragPan { filters = emptyList() } }.binding("dragPan").filters,
    )
  }

  @Test
  fun a_multi_filter_slot_has_no_single_filter_and_null_assignment_disables_matching() {
    val gestures = MapGestures {
      dragRotateTilt {
        assertNull(filter)
        filter = PointerFilter()
        assertEquals(PointerFilter(), filter)
        filter = null
        assertNull(filter)
        assertTrue(filters.isEmpty())
      }
    }
    assertTrue(gestures.binding("dragRotateTilt").filters.isEmpty())
  }

  @Test
  fun callback_identity_is_public_equality_but_not_structural_identity() {
    val calls = mutableListOf<String>()
    val first: (DragEvent.Start) -> Unit = { calls += "first" }
    val second: (DragEvent.Start) -> Unit = { calls += "second" }
    val a = MapGestures { dragPan { onStart(first) } }
    val b = MapGestures(from = a) { dragPan { onStart(second) } }
    assertNotEquals(a, b)
    assertEquals(a.structuralKey, b.structuralKey)
    assertEquals(a, MapGestures { dragPan { onStart(first) } })
    assertNotEquals(a.structuralKey, MapGestures.Standard.structuralKey)
    assertEquals(MapGestures.Standard, MapGestures(from = a) { dragPan { onStart(null) } })
    assertTrue(calls.isEmpty())
  }

  @Test
  fun predicates_custom_responses_and_tap_callbacks_use_the_same_identity_contract() {
    val a = MapGestures {
      drag("handle") {
        canStart { true }
        action = DragAction.Custom
        onEvent {}
      }
      tap { onEvent { ClickResult.Pass } }
    }
    val b = MapGestures {
      drag("handle") {
        canStart { false }
        action = DragAction.Custom
        onEvent {}
      }
      tap { onEvent { ClickResult.Consume } }
    }
    assertNotEquals(a, b)
    assertEquals(a.structuralKey, b.structuralKey)
    assertNotEquals(
      a.structuralKey,
      MapGestures {
        drag("handle") {
          canStart { true }
          action = DragAction.Pan
        }
        tap { onEvent { ClickResult.Pass } }
      }
        .structuralKey,
    )
  }

  @Test
  fun every_structural_edit_changes_the_restart_key() {
    val edits: List<MapGestures.Builder.() -> Unit> =
      listOf(
        { dragPan { enabled = false } },
        { dragPan { filter = PointerFilter(button = PointerButton.Secondary) } },
        { dragPan { startSlop = 8.dp } },
        { dragPan { continuation = null } },
        { pinchZoom { anchor = GestureAnchor.CameraCenter } },
        { doubleTap { cameraAction = null } },
        { scrollPan { kinds = ScrollKind.entries.toSet() } },
        { scrollIdleDuration = 50.milliseconds },
        { animationDuration = Duration.ZERO },
        { keys { clearZoom() } },
        { keys { rotaryZoom { enabled = false } } },
      )
    edits.forEach {
      assertNotEquals(MapGestures.Standard.structuralKey, MapGestures(block = it).structuralKey)
    }
  }

  @Test
  fun none_disables_every_family_and_rotary_but_can_be_edited() {
    assertTrue(MapGestures.None.bindings.all { !it.enabled })
    assertFalse(MapGestures.None.keyBindings.hasCameraBindings)
    assertFalse(MapGestures.None.keyBindings.rotary.enabled)
    assertTrue(MapGestures.None.keyBindings.chords.isEmpty())
    val panOnly = MapGestures(from = MapGestures.None) { dragPan { enabled = true } }
    assertEquals(listOf("dragPan"), panOnly.bindings.filter { it.enabled }.map { it.id })
    assertFalse(panOnly.keyBindings.rotary.enabled)
  }

  @Test
  fun presets_cover_new_pan_responses_and_all_position_changing_anchors() {
    for (gestures in listOf(MapGestures.PositionLocked, MapGestures.ZoomOnly)) {
      listOf("dragPan", "scrollPan", "boxZoom").forEach {
        assertFalse(gestures.binding(it).enabled)
      }
      assertTrue(
        gestures.bindings
          .filter {
            it.enabled && it.family != GestureFamily.Hover && it.family != GestureFamily.Shove
          }
          .all { it.settings.anchor == GestureAnchor.CameraCenter }
      )
      assertFalse(gestures.keyBindings.chords.values.any { it.name.startsWith("Pan") })
    }
    for (gestures in listOf(MapGestures.RotationLocked, MapGestures.ZoomOnly)) {
      listOf("dragRotateTilt", "twoFingerRotate", "twoFingerTilt").forEach {
        assertFalse(gestures.binding(it).enabled)
      }
      assertFalse(
        gestures.keyBindings.chords.values.any {
          it.name.startsWith("Rotate") || it.name.startsWith("Tilt")
        }
      )
    }
    assertTrue(MapGestures.RotationLocked.binding("dragPan").enabled)
    assertTrue(MapGestures.PositionLocked.binding("twoFingerRotate").enabled)
  }

  @Test
  fun explicit_drag_slop_applies_to_mouse_and_touch_unless_mouse_is_overridden() {
    assertEquals(4.dp, MapGestures.Standard.binding("dragPan").settings.startSlop)
    assertEquals(3.dp, MapGestures.Standard.binding("dragPan").settings.mouseStartSlop)
    assertEquals(3.dp, MapGestures.Standard.binding("boxZoom").settings.startSlop)
    val gestures = MapGestures { dragPan { startSlop = 5.dp } }
    assertEquals(5.dp, gestures.binding("dragPan").settings.startSlop)
    assertEquals(5.dp, gestures.binding("dragPan").settings.mouseStartSlop)
    val mouseOverride = MapGestures(from = gestures) { dragPan { mouseStartSlop = 2.dp } }
    assertEquals(5.dp, mouseOverride.binding("dragPan").settings.startSlop)
    assertEquals(2.dp, mouseOverride.binding("dragPan").settings.mouseStartSlop)
  }

  @Test
  fun thresholds_and_durations_reject_negative_and_nonfinite_values_but_accept_zero() {
    for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -1.0)) {
      assertFailsWith<IllegalArgumentException> { MapGestures { dragPan { startSlop = value.dp } } }
      assertFailsWith<IllegalArgumentException> {
        MapGestures { dragPan { mouseStartSlop = value.dp } }
      }
      assertFailsWith<IllegalArgumentException> {
        MapGestures { pinchZoom { startSpanSlop = value.dp } }
      }
      assertFailsWith<IllegalArgumentException> {
        MapGestures { twoFingerRotate { startAngle = value } }
      }
      assertFailsWith<IllegalArgumentException> { Fling(minimumSpeed = value) }
      assertFailsWith<IllegalArgumentException> { Fling(durationScale = value) }
      assertFailsWith<IllegalArgumentException> {
        GestureVelocityContinuation(durationScale = value)
      }
      assertFailsWith<IllegalArgumentException> { TiltContinuation(minimumSpeed = value) }
    }
    for (value in listOf(Duration.INFINITE, -Duration.INFINITE, (-1).milliseconds)) {
      assertFailsWith<IllegalArgumentException> { MapGestures { animationDuration = value } }
      assertFailsWith<IllegalArgumentException> { MapGestures { scrollIdleDuration = value } }
      assertFailsWith<IllegalArgumentException> {
        MapGestures { keys { rotaryZoom { idleDuration = value } } }
      }
      assertFailsWith<IllegalArgumentException> { Fling(baseTime = value) }
      assertFailsWith<IllegalArgumentException> {
        GestureVelocityContinuation(maximumDuration = value)
      }
      assertFailsWith<IllegalArgumentException> { TiltContinuation(duration = value) }
    }
    MapGestures {
      animationDuration = Duration.ZERO
      scrollIdleDuration = Duration.ZERO
      dragPan {
        startSlop = 0.dp
        continuation = Fling(0.0, Duration.ZERO, 0.0)
      }
      pinchZoom {
        startSpanSlop = 0.dp
        continuation = GestureVelocityContinuation(0.0, Duration.ZERO)
      }
      twoFingerRotate { startAngle = 0.0 }
      twoFingerTilt {
        startSlop = 0.dp
        continuation = TiltContinuation(0.0, Duration.ZERO)
      }
    }
  }

  @Test
  fun finite_signed_sensitivities_allow_inversion() {
    MapGestures {
      dragRotateTilt {
        bearingDegreesPerDp = -1.0
        pitchDegreesPerDp = 0.5
      }
      twoFingerTilt { pitchDegreesPerDp = 0.1 }
      pinchZoom { zoomScale = -1.0 }
      twoFingerRotate { rotationScale = -1.0 }
      scrollZoom { zoomStep = -0.15 }
      keys { panStep = (-100).dp }
    }
    for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      assertFailsWith<IllegalArgumentException> {
        MapGestures { dragRotateTilt { bearingDegreesPerDp = value } }
      }
      assertFailsWith<IllegalArgumentException> {
        MapGestures { twoFingerTilt { pitchDegreesPerDp = value } }
      }
      assertFailsWith<IllegalArgumentException> { MapGestures { pinchZoom { zoomScale = value } } }
      assertFailsWith<IllegalArgumentException> { MapGestures { keys { zoomStep = value } } }
      assertFailsWith<IllegalArgumentException> { MapGestures { keys { panStep = value.dp } } }
      assertFailsWith<IllegalArgumentException> {
        MapGestures { keys { rotaryZoom { zoomStep = value } } }
      }
    }
  }

  @Test
  fun keyboard_chords_are_exact_and_include_shifted_plus() {
    val chords = MapGestures.Standard.keyBindings.chords
    assertEquals(GestureKeyAction.PanLeft, chords[KeyChord(Key.DirectionLeft)])
    assertEquals(
      GestureKeyAction.RotateLeft,
      chords[KeyChord(Key.DirectionLeft, KeyModifier.Shift)],
    )
    assertNull(chords[KeyChord(Key.DirectionLeft, KeyModifier.Ctrl)])
    for (key in listOf(Key.Plus, Key.Equals)) {
      assertEquals(GestureKeyAction.ZoomIn, chords[KeyChord(key)])
      assertEquals(GestureKeyAction.ZoomIn, chords[KeyChord(key, KeyModifier.Shift)])
    }
    val gestures = MapGestures { keys { bind(KeyChord(Key.Enter), GestureKeyAction.ZoomIn) } }
    assertEquals(GestureKeyAction.ZoomIn, gestures.keyBindings.chords[KeyChord(Key.Enter)])
  }

  @Test
  fun clearing_zoom_preserves_rotary_and_other_chords() {
    val gestures = MapGestures { keys { clearZoom() } }
    assertFalse(
      gestures.keyBindings.chords.values.any {
        it == GestureKeyAction.ZoomIn || it == GestureKeyAction.ZoomOut
      }
    )
    assertTrue(gestures.keyBindings.rotary.enabled)
    assertTrue(gestures.keyBindings.hasCameraBindings)
    assertEquals(GestureKeyAction.Back, gestures.keyBindings.chords[KeyChord(Key.Back)])
  }
}
