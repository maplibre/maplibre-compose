package org.maplibre.compose.interaction.internal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.interaction.DragBindingBuilder
import org.maplibre.compose.interaction.DragResponse
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.KeyResponse
import org.maplibre.compose.interaction.ModifierMatch
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.compose.interaction.ScrollResponse

class MapInteractionsTest {
  private fun sample(
    type: PointerType = PointerType.Mouse,
    buttons: Set<PointerButton> = setOf(PointerButton.Primary),
    modifiers: Set<KeyModifier> = emptySet(),
  ) = GesturePointerSample(0, DpOffset.Zero, null, setOf(type), buttons, modifiers)

  @Test
  fun camera_policy_and_terminal_none_share_ordered_routing() {
    val standard = InputConfiguration.Standard
    val ctrlShift = sample(modifiers = setOf(KeyModifier.Ctrl, KeyModifier.Shift))
    assertEquals(
      DragResponse.RotateTilt,
      standard.bindings.drag.select(ctrlShift, standard.camera.settings),
    )
    val panLocked = InputConfiguration {
      camera { pan { enabled = false } }
      bindings {
        scroll {
          mappings {
            on(response = ScrollResponse.Pan)
            otherwise(ScrollResponse.Zoom)
          }
        }
      }
    }
    assertEquals(
      ScrollResponse.Zoom,
      panLocked.bindings.scroll.select(sample(), panLocked.camera.settings),
    )
    val excluded = InputConfiguration {
      bindings {
        scroll {
          mappings {
            on(
              modifiers = ModifierMatch.Containing(KeyModifier.Ctrl),
              response = ScrollResponse.None,
            )
            otherwise(ScrollResponse.Zoom)
          }
        }
      }
    }
    assertEquals(
      ScrollResponse.None,
      excluded.bindings.scroll.select(ctrlShift, excluded.camera.settings),
    )
    for (lockPan in listOf(false, true)) {
      val locked = InputConfiguration {
        camera {
          pan { enabled = !lockPan }
          zoom { enabled = lockPan }
        }
        bindings { drag { mappings { otherwise(DragResponse.FitBounds) } } }
      }
      assertNull(locked.bindings.drag.select(sample(), locked.camera.settings))
    }
  }

  @Test
  fun non_mouse_slop_edits_preserve_inherited_mouse_thresholds() {
    val base = InputConfiguration {
      bindings {
        drag {
          pan { mouseStartSlop = 9.dp }
          rotateTilt { mouseStartSlop = 9.dp }
          fitBounds { mouseStartSlop = 9.dp }
        }
      }
    }
    val edited =
      InputConfiguration(from = base) {
          bindings {
            drag {
              pan { startSlop = 12.dp }
              rotateTilt { startSlop = 12.dp }
              fitBounds { startSlop = 12.dp }
            }
          }
        }
        .bindings
        .drag
    for ((start, mouse) in
      listOf(
        edited.pan.startSlop to edited.pan.mouseStartSlop,
        edited.rotateTilt.startSlop to edited.rotateTilt.mouseStartSlop,
        edited.fitBounds.startSlop to edited.fitBounds.mouseStartSlop,
      )) {
      assertEquals(12.dp, start)
      assertEquals(9.dp, mouse)
    }
  }

  @Test
  fun mappings_are_replaced_and_tuning_does_not_restore_them() {
    val cleared = InputConfiguration { bindings { doubleTap { mappings {} } } }
    val tuned = InputConfiguration(from = cleared) { bindings { doubleTap { zoomStep = 2.0 } } }
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
        none.camera.settings.tilt.enabled
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
            on(Key.Plus, response = KeyResponse.None)
            on(Key.Plus, response = KeyResponse.ZoomIn)
            on(Key.Enter, response = KeyResponse.Engage)
          }
        }
      }
    }
    assertFalse(hidden.hasCameraKeys)
    assertEquals(
      KeyResponse.None,
      hidden.bindings.keys.select(Key.Plus, emptySet(), hidden.camera.settings),
    )
    val locked = InputConfiguration {
      camera { zoom { enabled = false } }
      bindings {
        keys {
          mappings {
            on(Key.Plus, response = KeyResponse.ZoomIn)
            on(Key.Enter, response = KeyResponse.Engage)
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
        keys { mappings { on(Key.DirectionLeft, response = KeyResponse.PanLeft) } }
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
              on(Key.DirectionLeft, modifiers = null, response = KeyResponse.PanLeft)
            }
          }
        }
      }
    assertTrue(wildcard.bindings.transform.pan.matches(modified))
    assertEquals(
      KeyResponse.PanLeft,
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
              otherwise(ScrollResponse.Pan)
              on(response = ScrollResponse.Zoom)
            }
          }
        }
      }
    }
  }
}
