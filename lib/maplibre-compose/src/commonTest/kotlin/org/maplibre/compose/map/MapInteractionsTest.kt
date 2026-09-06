package org.maplibre.compose.map

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
import kotlin.time.Duration.Companion.milliseconds

class MapInteractionsTest {
  private fun sample(
    type: PointerType = PointerType.Mouse,
    buttons: Set<PointerButton> = setOf(PointerButton.Primary),
    modifiers: Set<KeyModifier> = emptySet(),
  ) = GesturePointerSample(1, 0, DpOffset.Zero, null, setOf(type), buttons, modifiers)

  @Test
  fun camera_policy_and_terminal_none_share_ordered_routing() {
    val standard = MapInteractions.Standard
    val ctrlShift = sample(modifiers = setOf(KeyModifier.Ctrl, KeyModifier.Shift))
    assertEquals(DragResponse.RotateTilt, standard.bindings.drag.select(ctrlShift, standard.camera))
    val panLocked = MapInteractions {
      camera { pan { enabled = false } }
      bindings {
        scroll {
          mappings {
            on { pan() }
            otherwise { zoom() }
          }
        }
      }
    }
    assertEquals(
      ScrollResponse.Zoom,
      panLocked.bindings.scroll.select(sample(), panLocked.camera),
    )
    val excluded = MapInteractions {
      bindings {
        scroll {
          mappings {
            on(modifiers = ModifierMatch.Containing(KeyModifier.Ctrl)) { none() }
            otherwise { zoom() }
          }
        }
      }
    }
    assertEquals(
      ScrollResponse.None,
      excluded.bindings.scroll.select(ctrlShift, excluded.camera),
    )
    for (lockPan in listOf(false, true)) {
      val locked = MapInteractions {
        camera {
          pan { enabled = !lockPan }
          zoom { enabled = lockPan }
        }
        bindings { drag { mappings { otherwise { fitBounds() } } } }
      }
      assertNull(locked.bindings.drag.select(sample(), locked.camera))
    }
  }

  @Test
  fun non_mouse_slop_edits_preserve_inherited_mouse_thresholds() {
    val base = MapInteractions {
      bindings {
        drag {
          pan { mouseStartSlop = 9.dp }
          rotateTilt { mouseStartSlop = 9.dp }
          fitBounds { mouseStartSlop = 9.dp }
          custom("handle") {
            mouseStartSlop = 9.dp
            canStart { true }
            onEvent {}
          }
        }
      }
    }
    val edited =
      MapInteractions(from = base) {
          bindings {
            drag {
              pan { startSlop = 12.dp }
              rotateTilt { startSlop = 12.dp }
              fitBounds { startSlop = 12.dp }
              custom("handle") { startSlop = 12.dp }
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
        edited.custom.single().startSlop to edited.custom.single().mouseStartSlop,
      )) {
      assertEquals(12.dp, start)
      assertEquals(9.dp, mouse)
    }
  }

  @Test
  fun mappings_are_replaced_and_tuning_does_not_restore_them() {
    val cleared = MapInteractions { bindings { doubleTap { mappings {} } } }
    val tuned = MapInteractions(from = cleared) { bindings { doubleTap { zoomStep = 2.0 } } }
    assertTrue(tuned.bindings.doubleTap.mappings.isEmpty())
    assertTrue(tuned.bindings.doubleTap.enabled)
    assertEquals(MapInteractions.Standard.bindings.drag.mappings, tuned.bindings.drag.mappings)
    assertTrue(MapInteractions.Standard.bindings.tap.mappings.isEmpty())
    assertTrue(MapInteractions.Standard.bindings.secondaryClick.mappings.isEmpty())
    assertTrue(MapInteractions.Standard.bindings.longPress.mappings.isEmpty())
  }

  @Test
  fun none_does_not_restore_camera_mappings_when_a_family_is_enabled() {
    val none = MapInteractions.None
    assertTrue(
      none.camera.pan.enabled &&
        none.camera.zoom.enabled &&
        none.camera.rotate.enabled &&
        none.camera.tilt.enabled
    )
    val appOnly =
      MapInteractions(from = none) {
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
    assertFalse(appOnly.bindings.hover.enabled)
    assertFalse(appOnly.bindings.keys.hasCameraBindings(appOnly.camera))
  }

  @Test
  fun momentum_overrides_inherit_final_camera_fields_independent_of_block_order() {
    val interactions = MapInteractions {
      bindings {
        transform {
          pan {
            momentum { minimumSpeed = 250.0 }
            momentum { durationScale = 2.0 }
          }
        }
      }
      camera {
        pan {
          momentum {
            enabled = false
            baseTime = 500.milliseconds
          }
        }
      }
    }
    val pan = interactions.bindings.transform.pan.momentum
    assertEquals(250.0, pan.minimumSpeed)
    assertEquals(2.0, pan.durationScale)
    assertEquals(500.milliseconds, pan.baseTime)
    assertFalse(pan.enabled)
    val edited =
      MapInteractions(from = interactions) {
        camera { pan { momentum { baseTime = 600.milliseconds } } }
      }
    assertEquals(600.milliseconds, edited.bindings.transform.pan.momentum.baseTime)
    assertEquals(250.0, edited.bindings.transform.pan.momentum.minimumSpeed)
  }

  @Test
  fun keyboard_demand_requires_a_reachable_permitted_camera_row() {
    val hidden = MapInteractions {
      bindings {
        keys {
          mappings {
            on(Key.Plus) { none() }
            on(Key.Plus) { zoomIn() }
            on(Key.Enter) { engage() }
          }
        }
      }
    }
    assertFalse(hidden.bindings.keys.hasCameraBindings(hidden.camera))
    assertNull(hidden.bindings.keys.select(Key.Enter, emptySet(), hidden.camera))
    assertEquals(KeyResponse.None, hidden.bindings.keys.select(Key.Plus, emptySet(), hidden.camera))
    val locked = MapInteractions {
      camera { zoom { enabled = false } }
      bindings {
        keys {
          mappings {
            on(Key.Plus) { zoomIn() }
            on(Key.Enter) { engage() }
          }
        }
      }
    }
    assertFalse(locked.bindings.keys.hasCameraBindings(locked.camera))
    assertNull(
      MapInteractions.Standard.bindings.keys.select(
        Key.DirectionLeft,
        setOf(KeyModifier.Alt),
        MapInteractions.Standard.camera,
      )
    )
  }

  @Test
  fun custom_keys_keep_declaration_order_and_inherit_handlers() {
    val initial = MapInteractions {
      bindings {
        drag {
          custom("first") {
            canStart { true }
            onEvent {}
          }
          custom("second") {
            canStart { false }
            onEvent {}
          }
        }
      }
    }
    val edited =
      MapInteractions(from = initial) {
        bindings { drag { custom("first") { startSlop = 8.dp } } }
      }
    assertEquals(listOf("first", "second"), edited.bindings.drag.custom.map { it.key })
    assertEquals(
      initial.bindings.drag.custom.first().onEvent,
      edited.bindings.drag.custom.first().onEvent,
    )
    assertFailsWith<IllegalArgumentException> {
      MapInteractions { bindings { drag { custom("missing") { canStart { true } } } } }
    }
    assertFailsWith<IllegalArgumentException> {
      MapInteractions(from = initial) {
        bindings {
          drag {
            custom("first") {}
            custom("first") {}
          }
        }
      }
    }
  }

  @Test
  fun snapshots_and_mapping_rows_validate_at_configuration_time() {
    val types = mutableSetOf(PointerType.Mouse)
    lateinit var retained: DragBindingBuilder
    val value = MapInteractions {
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
      MapInteractions { bindings { scroll { mappings { otherwise {} } } } }
    }
    assertFailsWith<IllegalArgumentException> {
      MapInteractions {
        bindings {
          scroll {
            mappings {
              otherwise {
                pan()
                zoom()
              }
            }
          }
        }
      }
    }
    assertFailsWith<IllegalArgumentException> {
      MapInteractions {
        bindings {
          scroll {
            mappings {
              otherwise { pan() }
              on { zoom() }
            }
          }
        }
      }
    }
  }
}
