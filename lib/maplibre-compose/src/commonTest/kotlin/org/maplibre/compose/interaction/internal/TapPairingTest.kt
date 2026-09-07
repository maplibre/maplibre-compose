package org.maplibre.compose.interaction.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TapPairingTest {
  private val sample =
    GesturePointerSample(0, DpOffset.Zero, null, setOf(PointerType.Touch), emptySet(), emptySet())

  @Test
  fun touch_click_waits_for_expiry_and_preserves_its_camera_generation() = runTest {
    val delivered = mutableListOf<Pair<GesturePointerSample, Long>>()
    val pairing =
      TapPairing(backgroundScope, 40, 300) { sample, generation ->
        delivered += sample to generation
      }
    pairing.remember(sample, 7, Offset.Zero, PointerType.Touch, true, true)
    advanceTimeBy(299)
    runCurrent()
    assertEquals(emptyList(), delivered)
    advanceTimeBy(1)
    runCurrent()
    assertEquals(listOf(sample to 7L), delivered)
    pairing.discard(emitClick = true)
    assertEquals(1, delivered.size)
  }

  @Test
  fun a_bounce_leaves_the_first_tap_available_but_a_pair_claims_it() = runTest {
    var clicks = 0
    val pairing = TapPairing(backgroundScope, 40, 300) { _, _ -> clicks++ }
    pairing.remember(sample, 0, Offset.Zero, PointerType.Touch, true, true)
    assertEquals(
      TapPairing.Press.Bounce,
      pairing.press(Offset.Zero, 10, PointerType.Touch, 20f, true),
    )
    assertEquals(
      TapPairing.Press.Paired,
      pairing.press(Offset.Zero, 80, PointerType.Touch, 20f, true),
    )
    advanceTimeBy(400)
    runCurrent()
    assertEquals(0, clicks)
    pairing.discard(emitClick = false)
    assertEquals(0, clicks)
  }

  @Test
  fun incompatible_presses_release_the_pending_click_instead_of_pairing() = runTest {
    for ((position, time, type, useful) in
      listOf(
        Case(Offset(21f, 0f), 80, PointerType.Touch, true),
        Case(Offset.Zero, 301, PointerType.Touch, true),
        Case(Offset.Zero, 80, PointerType.Mouse, true),
        Case(Offset.Zero, 80, PointerType.Touch, false),
      )) {
      var clicks = 0
      val pairing = TapPairing(backgroundScope, 40, 300) { _, _ -> clicks++ }
      pairing.remember(sample, 0, Offset.Zero, PointerType.Touch, true, true)
      assertEquals(TapPairing.Press.First, pairing.press(position, time, type, 20f, useful))
      assertEquals(1, clicks)
      advanceTimeBy(400)
      runCurrent()
      assertEquals(1, clicks)
    }
  }

  @Test
  fun a_claimed_touch_click_can_fall_back_to_a_drag_but_mouse_clicks_are_not_repeated() = runTest {
    for (type in listOf(PointerType.Touch, PointerType.Mouse)) {
      var clicks = 0
      val pairing = TapPairing(backgroundScope, 40, 300) { _, _ -> clicks++ }
      pairing.remember(sample, 0, Offset.Zero, type, true, type == PointerType.Touch)
      assertEquals(TapPairing.Press.Paired, pairing.press(Offset.Zero, 80, type, 20f, true))
      pairing.discard(emitClick = true)
      advanceTimeBy(400)
      runCurrent()
      assertEquals(if (type == PointerType.Touch) 1 else 0, clicks)
    }
  }

  private data class Case(
    val position: Offset,
    val time: Long,
    val type: PointerType,
    val useful: Boolean,
  )
}
