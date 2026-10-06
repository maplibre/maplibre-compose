package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.interaction.CameraAction
import org.maplibre.compose.interaction.DragBindingBuilder
import org.maplibre.compose.interaction.FocusAction
import org.maplibre.compose.interaction.InputAction
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.ModifierMatch
import org.maplibre.compose.interaction.PointerButton

class MapInteractionsTest {
  private fun sample(
    type: PointerType = PointerType.Mouse,
    buttons: Set<PointerButton> = setOf(PointerButton.Primary),
    modifiers: Set<KeyModifier> = emptySet(),
  ) = GesturePointerSample(0, DpOffset.Zero, null, setOf(type), buttons, modifiers)

  @Test
  fun unknown_contact_supports_standard_touch_gestures() {
    val standard = InputConfiguration.Standard
    val contact = sample(type = PointerType.Unknown, buttons = emptySet())
    assertEquals(
      CameraAction.Pan,
      standard.bindings.drag.select(contact, standard.camera.settings),
    )
    for (family in
      listOf(TapFamily.Tap, TapFamily.DoubleTap, TapFamily.LongPress, TapFamily.TwoFingerTap)) {
      assertTrue(family.matches(standard, contact), "$family should accept unknown contact")
    }
    assertTrue(standard.bindings.tapDrag.matches(contact))
    assertFalse(TapFamily.SecondaryClick.matches(standard, contact))
  }

  @Test
  fun camera_policy_and_terminal_none_share_ordered_routing() {
    val standard = InputConfiguration.Standard
    val ctrlShift = sample(modifiers = setOf(KeyModifier.Ctrl, KeyModifier.Shift))
    assertEquals(
      CameraAction.RotatePitch,
      standard.bindings.drag.select(ctrlShift, standard.camera.settings),
    )
    val panLocked = InputConfiguration {
      camera { pan { enabled = false } }
      bindings {
        scroll {
          mappings {
            on(action = CameraAction.Pan)
            otherwise(CameraAction.Zoom)
          }
        }
      }
    }
    assertEquals(
      CameraAction.Zoom,
      panLocked.bindings.scroll.select(sample(), panLocked.camera.settings),
    )
    val excluded = InputConfiguration {
      bindings {
        scroll {
          mappings {
            on(
              modifiers = ModifierMatch.Containing(KeyModifier.Ctrl),
              action = InputAction.None,
            )
            otherwise(CameraAction.Zoom)
          }
        }
      }
    }
    assertEquals(
      InputAction.None,
      excluded.bindings.scroll.select(ctrlShift, excluded.camera.settings),
    )
    for (lockPan in listOf(false, true)) {
      val locked = InputConfiguration {
        camera {
          pan { enabled = !lockPan }
          zoom { enabled = lockPan }
        }
        bindings { drag { mappings { otherwise(CameraAction.FitBounds) } } }
      }
      assertNull(locked.bindings.drag.select(sample(), locked.camera.settings))
    }
  }

  @Test
  fun pan_slop_can_restore_host_default_but_rejects_invalid_distances() {
    val explicit = InputConfiguration { bindings { drag { pan { startSlop = 8.dp } } } }
    val restored =
      InputConfiguration(explicit) {
        bindings { drag { pan { startSlop = Dp.Unspecified } } }
      }
    assertEquals(Dp.Unspecified, InputConfiguration.Standard.bindings.drag.pan.startSlop)
    assertEquals(Dp.Unspecified, restored.bindings.drag.pan.startSlop)
    for (invalid in listOf((-1).dp, Dp.Infinity)) {
      assertFailsWith<IllegalArgumentException> {
        InputConfiguration { bindings { drag { pan { startSlop = invalid } } } }
      }
    }
  }

  @Test
  fun non_mouse_slop_edits_preserve_inherited_mouse_thresholds() {
    val base = InputConfiguration {
      bindings {
        drag {
          pan { mouseStartSlop = 9.dp }
          rotatePitch { mouseStartSlop = 9.dp }
          fitBounds { mouseStartSlop = 9.dp }
        }
      }
    }
    val edited =
      InputConfiguration(from = base) {
          bindings {
            drag {
              pan { startSlop = 12.dp }
              rotatePitch { startSlop = 12.dp }
              fitBounds { startSlop = 12.dp }
            }
          }
        }
        .bindings
        .drag
    for ((start, mouse) in
      listOf(
        edited.pan.startSlop to edited.pan.mouseStartSlop,
        edited.rotatePitch.startSlop to edited.rotatePitch.mouseStartSlop,
        edited.fitBounds.startSlop to edited.fitBounds.mouseStartSlop,
      )) {
      assertEquals(12.dp, start)
      assertEquals(9.dp, mouse)
    }
  }

  @Test
  fun standard_tap_drag_yields_to_modifier_drags_and_secondary_button() {
    val standard = InputConfiguration.Standard
    assertTrue(standard.bindings.tapDrag.matches(sample()))
    assertFalse(standard.bindings.tapDrag.matches(sample(buttons = setOf(PointerButton.Secondary))))
    val shifted = sample(modifiers = setOf(KeyModifier.Shift))
    assertFalse(standard.bindings.tapDrag.matches(shifted))
    assertEquals(
      CameraAction.FitBounds,
      standard.bindings.drag.select(shifted, standard.camera.settings),
    )
  }

  @Test
  fun mappings_are_replaced_and_tuning_does_not_restore_them() {
    val cleared = InputConfiguration { bindings { doubleTap { mappings {} } } }
    val tuned =
      InputConfiguration(from = cleared) { bindings { doubleTap { zoomStepLevels = 2.0 } } }
    assertTrue(tuned.bindings.doubleTap.mappings.isEmpty())
    assertTrue(tuned.bindings.doubleTap.enabled)
    assertEquals(InputConfiguration.Standard.bindings.drag.mappings, tuned.bindings.drag.mappings)
    assertTrue(InputConfiguration.Standard.bindings.tap.mappings.isEmpty())
    assertTrue(InputConfiguration.Standard.bindings.secondaryClick.mappings.isEmpty())
    assertTrue(InputConfiguration.Standard.bindings.longPress.mappings.isEmpty())
  }

  @Test
  fun no_bindings_does_not_restore_camera_mappings_when_a_family_is_enabled() {
    val none = InputConfiguration.NoBindings
    assertTrue(
      none.camera.settings.pan.enabled &&
        none.camera.settings.zoom.enabled &&
        none.camera.settings.rotate.enabled &&
        none.camera.settings.pitch.enabled
    )
    val appOnly =
      InputConfiguration(from = none) {
        bindings {
          tap { enabled = true }
          drag { enabled = true }
          transform { zoom { enabled = true } }
        }
      }
    assertTrue(appOnly.bindings.tap.mappings.isEmpty())
    assertTrue(appOnly.bindings.drag.mappings.isEmpty())
    assertTrue(appOnly.bindings.transform.zoom.enabled)
    assertFalse(appOnly.bindings.transform.pan.enabled)
    assertFalse(appOnly.hasCameraKeys)
  }

  @Test
  fun keyboard_demand_requires_a_reachable_permitted_camera_row() {
    val hidden = InputConfiguration {
      bindings {
        keys {
          mappings {
            on(Key.Plus, action = InputAction.None)
            on(Key.Plus, action = CameraAction.ZoomIn)
            on(Key.Enter, action = FocusAction.Engage)
          }
        }
      }
    }
    assertFalse(hidden.hasCameraKeys)
    assertEquals(
      InputAction.None,
      hidden.bindings.keys.select(Key.Plus, emptySet(), hidden.camera.settings),
    )
    val locked = InputConfiguration {
      camera { zoom { enabled = false } }
      bindings {
        keys {
          mappings {
            on(Key.Plus, action = CameraAction.ZoomIn)
            on(Key.Enter, action = FocusAction.Engage)
          }
        }
      }
    }
    assertFalse(locked.hasCameraKeys)
    assertNull(
      InputConfiguration.Standard.bindings.keys.select(
        Key.DirectionLeft,
        setOf(KeyModifier.Alt),
        InputConfiguration.Standard.camera.settings,
      )
    )
  }

  @Test
  fun null_modifiers_match_any_keys_and_restore_inherited_pointer_filters() {
    val base = InputConfiguration {
      bindings {
        transform { pan { modifiers = ModifierMatch.Exactly() } }
        keys { mappings { on(Key.DirectionLeft, action = CameraAction.PanLeft) } }
      }
    }
    val modified = sample(type = PointerType.Touch, modifiers = setOf(KeyModifier.Alt))
    assertFalse(base.bindings.transform.pan.matches(modified))
    assertNull(
      base.bindings.keys.select(Key.DirectionLeft, modified.modifierKeys, base.camera.settings)
    )
    val wildcard =
      InputConfiguration(from = base) {
        bindings {
          transform { pan { modifiers = null } }
          keys {
            mappings {
              on(Key.DirectionLeft, modifiers = null, action = CameraAction.PanLeft)
            }
          }
        }
      }
    assertTrue(wildcard.bindings.transform.pan.matches(modified))
    assertEquals(
      CameraAction.PanLeft,
      wildcard.bindings.keys.select(
        Key.DirectionLeft,
        modified.modifierKeys,
        wildcard.camera.settings,
      ),
    )
    assertTrue(wildcard.hasCameraKeys)
  }

  @Test
  fun snapshots_and_mapping_rows_validate_at_configuration_time() {
    val types = mutableSetOf(PointerType.Mouse)
    lateinit var retained: DragBindingBuilder
    val value = InputConfiguration {
      bindings {
        drag {
          retained = this
          pointerTypes = types
        }
      }
    }
    types.clear()
    retained.enabled = false
    assertEquals(setOf(PointerType.Mouse), value.bindings.drag.pointerTypes)
    assertTrue(value.bindings.drag.enabled)
    assertFailsWith<IllegalArgumentException> {
      InputConfiguration {
        bindings {
          scroll {
            mappings {
              otherwise(CameraAction.Pan)
              on(action = CameraAction.Zoom)
            }
          }
        }
      }
    }
  }
}
