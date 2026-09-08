package org.maplibre.compose.interaction.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.interaction.GestureAnchor
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.ModifierMatch
import org.maplibre.compose.map.GestureTestFixture

@OptIn(ExperimentalCoroutinesApi::class)
class MapPlatformTransformTest {
  private val map = GestureTestFixture()

  @AfterTest fun closeMap() = map.close()

  @Test
  fun missing_scale_start_uses_supplied_scale_gain_and_anchor() = runTest {
    val fixture =
      Fixture(
        backgroundScope,
        InputConfiguration {
          bindings {
            transform {
              zoom {
                zoomScale = 0.5
                anchor = GestureAnchor.CameraCenter
              }
            }
          }
        },
      )
    try {
      assertTrue(fixture.input.onInput(PointerEventType.ScaleChange, sample(10), scaleFactor = 2.0))
      fixture.input.onInput(PointerEventType.ScaleEnd, sample(20))
      fixture.input.onInput(PointerEventType.ScaleEnd, sample(20))
      runCurrent()
      assertEquals(sqrt(2.0), fixture.target.scaleCalls.single().scale, 1e-6)
      assertEquals(null, fixture.target.scaleCalls.single().anchor)
      assertEquals(1, fixture.target.endedCount)
    } finally {
      fixture.input.cancel()
    }
  }

  @Test
  fun pan_and_scale_overlap_in_one_session_and_end_without_added_momentum() = runTest {
    val fixture = Fixture(backgroundScope)
    try {
      fixture.input.onInput(PointerEventType.PanStart, sample(0))
      fixture.input.onInput(PointerEventType.ScaleStart, sample(0))
      fixture.input.onInput(
        PointerEventType.PanMove,
        sample(10),
        panDelta = DpOffset(20.dp, (-10).dp),
      )
      fixture.input.onInput(
        PointerEventType.PanMove,
        sample(20),
        panDelta = DpOffset(20.dp, (-10).dp),
      )
      fixture.input.onInput(PointerEventType.ScaleChange, sample(20), scaleFactor = 2.0)
      fixture.input.onInput(PointerEventType.PanEnd, sample(30))
      assertEquals(0, fixture.target.endedCount)
      fixture.input.onInput(PointerEventType.ScaleEnd, sample(30))
      runCurrent()
      assertEquals(1, fixture.target.startedCount)
      assertEquals(1, fixture.target.endedCount)
      assertEquals(2, fixture.target.moveCalls.size)
      assertEquals(1, fixture.target.scaleCalls.size)
    } finally {
      fixture.input.cancel()
    }
  }

  @Test
  fun disabled_zoom_does_not_interrupt_an_active_pan() = runTest {
    val fixture =
      Fixture(backgroundScope, InputConfiguration { camera { zoom { enabled = false } } })
    try {
      assertTrue(fixture.input.onInput(PointerEventType.PanStart, sample(0)))
      fixture.input.onInput(
        PointerEventType.PanMove,
        sample(10),
        panDelta = DpOffset(10.dp, 0.dp),
      )
      assertFalse(fixture.input.onInput(PointerEventType.ScaleStart, sample(20)))
      assertFalse(
        fixture.input.onInput(PointerEventType.ScaleChange, sample(30), scaleFactor = 2.0)
      )
      fixture.input.onInput(PointerEventType.ScaleEnd, sample(40))
      assertTrue(
        fixture.input.onInput(
          PointerEventType.PanMove,
          sample(50),
          panDelta = DpOffset(20.dp, 0.dp),
        )
      )
      assertEquals(listOf(Offset(10f, 0f), Offset(20f, 0f)), fixture.target.moveCalls)
      assertTrue(fixture.target.scaleCalls.isEmpty())
      assertEquals(1, fixture.target.startedCount)
      assertEquals(0, fixture.target.endedCount)

      fixture.input.onInput(PointerEventType.PanEnd, sample(60))
      runCurrent()
      assertEquals(1, fixture.target.endedCount)
    } finally {
      fixture.input.cancel()
    }
  }

  @Test
  fun invalid_deltas_do_not_claim_the_camera() = runTest {
    val fixture = Fixture(backgroundScope)
    try {
      for (value in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
        assertFalse(
          fixture.input.onInput(PointerEventType.ScaleChange, sample(0), scaleFactor = value)
        )
      }
      assertEquals(0, fixture.target.startedCount)
      fixture.input.onInput(PointerEventType.PanStart, sample(0))
      fixture.input.onInput(PointerEventType.PanMove, sample(10), panDelta = DpOffset(10.dp, 0.dp))
      fixture.input.onInput(PointerEventType.PanMove, sample(15), panDelta = DpOffset(4.dp, 0.dp))
      fixture.input.onInput(PointerEventType.PanEnd, sample(20))
      assertEquals(listOf(Offset(10f, 0f), Offset(4f, 0f)), fixture.target.moveCalls)
    } finally {
      fixture.input.cancel()
    }
  }

  @Test
  fun consumption_cancels_once_and_suppresses_the_stream_until_its_end() = runTest {
    val fixture = Fixture(backgroundScope)
    try {
      fixture.input.onInput(PointerEventType.ScaleStart, sample(0))
      assertFalse(
        fixture.input.onInput(
          PointerEventType.ScaleChange,
          sample(10),
          scaleFactor = 2.0,
          consumed = true,
        )
      )
      assertFalse(
        fixture.input.onInput(PointerEventType.ScaleChange, sample(20), scaleFactor = 2.0)
      )
      fixture.input.onInput(PointerEventType.ScaleEnd, sample(30))
      fixture.input.onInput(PointerEventType.ScaleStart, sample(40))
      fixture.input.onInput(PointerEventType.ScaleChange, sample(50), scaleFactor = 2.0)
      fixture.input.onInput(PointerEventType.ScaleEnd, sample(60))
      assertEquals(2, fixture.target.endedCount)
      assertEquals(1, fixture.target.scaleCalls.size)
      assertEquals(2, fixture.target.startedCount)
    } finally {
      fixture.input.cancel()
    }
  }

  @Test
  fun camera_start_takeover_prevents_response_and_stops_both_components() = runTest {
    lateinit var fixture: Fixture
    fixture =
      Fixture(
        backgroundScope,
        InputConfiguration {
          camera { zoom { onStart { fixture.target.onGestureStarted() } } }
        },
      )
    try {
      fixture.input.onInput(PointerEventType.PanStart, sample(0))
      fixture.input.onInput(PointerEventType.ScaleStart, sample(0))
      fixture.input.onInput(PointerEventType.ScaleChange, sample(10), scaleFactor = 2.0)
      runCurrent()
      assertTrue(fixture.target.scaleCalls.isEmpty())
      assertFalse(
        fixture.input.onInput(
          PointerEventType.PanMove,
          sample(20),
          panDelta = DpOffset(10.dp, 0.dp),
        )
      )
    } finally {
      fixture.input.cancel()
      map.close()
    }
  }

  @Test
  fun matching_modifier_changes_continue_the_same_stream() = runTest {
    val fixture = Fixture(backgroundScope)
    try {
      fixture.input.onInput(PointerEventType.PanStart, sample(0))
      val shifted = sample(10).copy(modifierKeys = setOf(KeyModifier.Shift))
      assertTrue(
        fixture.input.onInput(PointerEventType.PanMove, shifted, panDelta = DpOffset(10.dp, 0.dp))
      )
      assertTrue(
        fixture.input.onInput(
          PointerEventType.PanMove,
          sample(20),
          panDelta = DpOffset(10.dp, 0.dp),
        )
      )
      fixture.input.onInput(PointerEventType.PanEnd, sample(30))
      assertEquals(1, fixture.target.endedCount)
      assertEquals(listOf(Offset(10f, 0f), Offset(10f, 0f)), fixture.target.moveCalls)
      assertEquals(1, fixture.target.startedCount)
    } finally {
      fixture.input.cancel()
    }
  }

  @Test
  fun unavailable_map_leaves_transforms_unclaimed_until_the_host_ends_them() = runTest {
    val fixture = Fixture(backgroundScope)
    val viewport = fixture.target.currentViewport
    fixture.target.currentViewport = null
    assertFalse(fixture.input.onInput(PointerEventType.ScaleStart, sample(0)))
    fixture.target.currentViewport = viewport
    assertFalse(fixture.input.onInput(PointerEventType.ScaleChange, sample(10), scaleFactor = 2.0))
    assertFalse(fixture.input.onInput(PointerEventType.ScaleEnd, sample(20)))
    assertTrue(fixture.target.scaleCalls.isEmpty())
    assertTrue(fixture.input.onInput(PointerEventType.ScaleStart, sample(30)))
    assertEquals(1, fixture.target.startedCount)
    fixture.input.cancel()
  }

  @Test
  fun disabled_and_nonmatching_bindings_leave_platform_streams_unclaimed() = runTest {
    val fixture = Fixture(backgroundScope, InputConfiguration.NoBindings)
    assertFalse(fixture.input.onInput(PointerEventType.PanStart, sample(0)))
    assertFalse(fixture.input.onInput(PointerEventType.ScaleChange, sample(10), scaleFactor = 2.0))
    assertEquals(0, fixture.target.startedCount)
    fixture.input.cancel()
    val unmatched =
      Fixture(
        backgroundScope,
        InputConfiguration {
          bindings { transform { zoom { modifiers = ModifierMatch.Containing(KeyModifier.Ctrl) } } }
        },
      )
    assertFalse(unmatched.input.onInput(PointerEventType.ScaleStart, sample(0)))
    assertEquals(0, unmatched.target.startedCount)
    unmatched.input.cancel()
  }

  @Test
  fun classified_wrappers_remain_routed_and_blocked_until_all_reported_contacts_lift() {
    val routing = PlatformTransformRouting()
    fun change(id: Long, pressed: Boolean, previous: Boolean) =
      PointerInputChange(
        PointerId(id),
        0,
        Offset.Zero,
        pressed,
        0,
        Offset.Zero,
        previous,
        isInitiallyConsumed = false,
        type = PointerType.Mouse,
      )
    assertTrue(routing.route(PointerEventType.Press, true, listOf(change(1, true, false))))
    routing.intercept()
    assertTrue(routing.blocked)
    assertTrue(
      routing.route(
        PointerEventType.ScaleStart,
        true,
        listOf(change(1, true, true), change(2, true, false)),
      )
    )
    assertTrue(
      routing.route(
        PointerEventType.ScaleEnd,
        true,
        listOf(change(1, true, true), change(2, false, true)),
      )
    )
    assertTrue(routing.route(PointerEventType.Move, false, listOf(change(1, true, true))))
    assertTrue(routing.blocked)
    assertTrue(routing.route(PointerEventType.Release, false, listOf(change(1, false, true))))
    assertFalse(routing.blocked)
    assertFalse(routing.hasContacts)
    assertFalse(routing.route(PointerEventType.Press, false, listOf(change(1, true, false))))
  }

  private inner class Fixture(
    scope: CoroutineScope,
    initial: InputConfiguration = InputConfiguration.Standard,
  ) {
    init {
      map.state.gestureAuthority.updateConfiguration(initial.camera)
    }

    val target = map.target
    val routing = PlatformTransformRouting()

    val input =
      PlatformTransformSession(
        target,
        initial,
        scope,
        routing,
        {},
      )
  }

  companion object {
    private fun sample(time: Long) =
      GesturePointerSample(
        time,
        DpOffset(10.dp, 20.dp),
        null,
        setOf(PointerType.Mouse),
        emptySet(),
        emptySet(),
      )
  }
}
