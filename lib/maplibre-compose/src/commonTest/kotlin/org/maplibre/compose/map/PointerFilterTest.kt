package org.maplibre.compose.map

import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PointerFilterTest {
  @Test
  fun modifier_matching_distinguishes_any_exact_and_containing() {
    val ctrlShift = setOf(KeyModifier.Ctrl, KeyModifier.Shift)
    assertTrue(ModifierFilter.Any.matches(ctrlShift))
    assertFalse(ModifierFilter.Exactly(KeyModifier.Ctrl).matches(ctrlShift))
    assertTrue(ModifierFilter.Containing(KeyModifier.Ctrl).matches(ctrlShift))
    assertFalse(ModifierFilter.Exactly().matches(ctrlShift))
    assertTrue(ModifierFilter.Exactly().matches(emptySet()))
    assertTrue(ModifierFilter.Containing().matches(ctrlShift))
    assertFalse(ModifierFilter.Containing(KeyModifier.Alt).matches(ctrlShift))
  }

  @Test
  fun every_contact_must_match_the_reported_type_filter() {
    val filter = PointerFilter(setOf(PointerType.Touch), button = null)
    assertTrue(filter.matches(setOf(PointerType.Touch), emptySet(), emptySet(), contact = true))
    assertFalse(
      filter.matches(
        setOf(PointerType.Touch, PointerType.Stylus),
        emptySet(),
        emptySet(),
        contact = true,
      )
    )
    assertFalse(filter.matches(setOf(PointerType.Mouse), emptySet(), emptySet(), contact = true))
    assertTrue(
      PointerFilter(button = null)
        .matches(setOf(PointerType.Unknown), emptySet(), emptySet(), contact = true)
    )
  }

  @Test
  fun touch_and_stylus_match_logical_primary_but_scroll_requires_physical_buttons() {
    for (type in listOf(PointerType.Touch, PointerType.Stylus, PointerType.Eraser)) {
      assertTrue(PointerFilter().matches(setOf(type), emptySet(), emptySet(), contact = true))
      assertFalse(PointerFilter().matches(setOf(type), emptySet(), emptySet(), contact = false))
      assertFalse(
        PointerFilter(button = PointerButton.Secondary)
          .matches(setOf(type), emptySet(), emptySet(), contact = true)
      )
    }
    assertFalse(
      PointerFilter().matches(setOf(PointerType.Mouse), emptySet(), emptySet(), contact = false)
    )
    assertTrue(
      PointerFilter(button = null)
        .matches(setOf(PointerType.Mouse), emptySet(), emptySet(), contact = false)
    )
    assertTrue(
      PointerFilter()
        .matches(
          setOf(PointerType.Mouse),
          setOf(PointerButton.Primary),
          emptySet(),
          contact = false,
        )
    )
  }

  @Test
  fun classified_buttonless_mouse_transform_is_logical_primary_only_for_contacts() {
    assertFalse(
      PointerFilter()
        .matches(
          setOf(PointerType.Mouse),
          setOf(PointerButton.Secondary),
          emptySet(),
          contact = true,
          platformTransform = true,
        )
    )
    assertTrue(
      PointerFilter()
        .matches(
          setOf(PointerType.Mouse),
          emptySet(),
          emptySet(),
          contact = true,
          platformTransform = true,
        )
    )
    assertFalse(
      PointerFilter().matches(setOf(PointerType.Mouse), emptySet(), emptySet(), contact = true)
    )
    assertFalse(
      PointerFilter()
        .matches(
          setOf(PointerType.Mouse),
          emptySet(),
          emptySet(),
          contact = false,
          platformTransform = true,
        )
    )
    assertFalse(
      PointerFilter(setOf(PointerType.Touch))
        .matches(
          setOf(PointerType.Mouse),
          emptySet(),
          emptySet(),
          contact = true,
          platformTransform = true,
        )
    )
  }

  @Test
  fun default_or_filters_preserve_ctrl_shift_and_secondary_rotate_priority() {
    val defaults =
      MapGestures.Standard.bindings.filter {
        it.id in setOf("dragRotateTilt", "boxZoom", "dragPan")
      }
    fun candidates(button: PointerButton, modifiers: Set<KeyModifier>) =
      defaults
        .filter { binding ->
          binding.filters.any {
            it.matches(setOf(PointerType.Mouse), setOf(button), modifiers, contact = true)
          }
        }
        .map { it.id }
    assertEquals(
      listOf("dragRotateTilt", "boxZoom", "dragPan"),
      candidates(PointerButton.Primary, setOf(KeyModifier.Ctrl, KeyModifier.Shift)),
    )
    assertEquals(
      listOf("boxZoom", "dragPan"),
      candidates(PointerButton.Primary, setOf(KeyModifier.Shift)),
    )
    assertEquals(listOf("dragPan"), candidates(PointerButton.Primary, setOf(KeyModifier.Alt)))
    assertEquals(listOf("dragRotateTilt"), candidates(PointerButton.Secondary, emptySet()))
  }

  @Test
  fun pointer_event_snapshots_metadata_without_fabricating_projection_or_buttons() {
    val types = mutableSetOf(PointerType.Touch, PointerType.Stylus)
    val modifiers = mutableSetOf(KeyModifier.Ctrl)
    val buttons = mutableSetOf<PointerButton>()
    val event =
      TapEvent(
        GesturePointerSample(7L, 50L, DpOffset(12.dp, 34.dp), null, types, buttons, modifiers)
      )
    types.clear()
    modifiers.clear()
    buttons += PointerButton.Primary
    assertEquals(7L, event.gestureId)
    assertEquals(50L, event.uptimeMillis)
    assertEquals(DpOffset(12.dp, 34.dp), event.screenOffset)
    assertEquals(null, event.position)
    assertEquals(setOf(PointerType.Touch, PointerType.Stylus), event.pointerTypes)
    assertEquals(setOf(KeyModifier.Ctrl), event.modifierKeys)
    assertTrue(event.buttons.isEmpty())
  }
}
