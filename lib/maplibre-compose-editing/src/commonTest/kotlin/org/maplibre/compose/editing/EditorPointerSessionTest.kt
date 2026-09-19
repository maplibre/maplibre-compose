package org.maplibre.compose.editing

import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.maplibre.compose.editing.internal.EditorPointerHandler
import org.maplibre.compose.editing.internal.EditorPointerSession
import org.maplibre.compose.editing.internal.PointerSample

class EditorPointerSessionTest {
  private class Handler(var claim: Boolean = false, var consumeTap: Boolean = false) :
    EditorPointerHandler {
    val calls = mutableListOf<String>()
    val steps = mutableListOf<EditStep>()
    var dragResult = true
    var lastSample: PointerSample? = null

    override fun onPress(sample: PointerSample, step: EditStep): Boolean {
      calls += "press"
      steps += step
      return claim
    }

    override fun onDrag(sample: PointerSample, step: EditStep): Boolean {
      calls += "drag"
      steps += step
      lastSample = sample
      return dragResult
    }

    override fun onRelease(sample: PointerSample, step: EditStep) {
      calls += "release"
      steps += step
      lastSample = sample
    }

    override fun onTap(sample: PointerSample, count: Int, step: EditStep): Boolean {
      calls += "tap$count"
      steps += step
      return consumeTap
    }

    override fun onLongPress(sample: PointerSample, step: EditStep) {
      calls += "longPress"
      steps += step
      lastSample = sample
    }

    override fun onCancel(step: EditStep) {
      calls += "cancel"
      steps += step
    }
  }

  private fun session(handler: Handler) =
    EditorPointerSession(
      handler,
      touchSlop = { 18.dp },
      doubleTapTimeoutMillis = { 300 },
      doubleTapRadius = { 24.dp },
    )

  private fun sample(
    id: Long,
    pressed: Boolean,
    previousPressed: Boolean,
    x: Float,
    y: Float = 0f,
    time: Long = 0,
    type: PointerType = PointerType.Touch,
  ) =
    PointerSample(
      id,
      pressed,
      previousPressed,
      DpOffset(x.dp, y.dp),
      time,
      type,
      emptySet(),
      emptySet(),
    )

  private fun down(
    id: Long = 1,
    x: Float = 0f,
    time: Long = 0,
    type: PointerType = PointerType.Touch,
  ) = sample(id, pressed = true, previousPressed = false, x = x, time = time, type = type)

  private fun move(id: Long = 1, x: Float, time: Long = 0, type: PointerType = PointerType.Touch) =
    sample(id, pressed = true, previousPressed = true, x = x, time = time, type = type)

  private fun up(
    id: Long = 1,
    x: Float = 0f,
    time: Long = 0,
    type: PointerType = PointerType.Touch,
  ) = sample(id, pressed = false, previousPressed = true, x = x, time = time, type = type)

  @Test
  fun claimedPointerIsConsumedAndDraggedPastSlop() {
    val handler = Handler(claim = true)
    val session = session(handler)
    assertEquals(setOf(1L), session.onEvent(listOf(down())))
    assertTrue(session.isClaimed)
    assertEquals(setOf(1L), session.onEvent(listOf(move(x = 10f))))
    assertEquals(listOf("press"), handler.calls)
    assertEquals(setOf(1L), session.onEvent(listOf(move(x = 30f))))
    assertEquals(setOf(1L), session.onEvent(listOf(move(x = 31f))))
    assertEquals(setOf(1L), session.onEvent(listOf(up(x = 31f))))
    assertEquals(listOf("press", "drag", "drag", "release"), handler.calls)
    assertTrue(handler.steps.all { it === handler.steps[0] })
    assertFalse(session.isClaimed)
  }

  @Test
  fun mouseSlopIsFourDp() {
    val handler = Handler(claim = true)
    val session = session(handler)
    session.onEvent(listOf(down(type = PointerType.Mouse)))
    session.onEvent(listOf(move(x = 3f, type = PointerType.Mouse)))
    assertEquals(listOf("press"), handler.calls)
    session.onEvent(listOf(move(x = 5f, type = PointerType.Mouse)))
    assertEquals(listOf("press", "drag"), handler.calls)
  }

  @Test
  fun unclaimedTapIsDeliveredAndConsumedOnlyWhenTheHandlerSaysSo() {
    val handler = Handler()
    val session = session(handler)
    assertEquals(emptySet(), session.onEvent(listOf(down())))
    assertEquals(emptySet(), session.onEvent(listOf(move(x = 5f))))
    assertEquals(emptySet(), session.onEvent(listOf(up(x = 5f))))
    assertEquals(listOf("press", "tap1"), handler.calls)

    handler.consumeTap = true
    session.onEvent(listOf(down(time = 1000)))
    assertEquals(setOf(1L), session.onEvent(listOf(up(time = 1010))))
    assertEquals(listOf("press", "tap1", "press", "tap1"), handler.calls)
  }

  @Test
  fun unclaimedPointerPastSlopIsNoTap() {
    val handler = Handler()
    val session = session(handler)
    session.onEvent(listOf(down()))
    session.onEvent(listOf(move(x = 40f)))
    session.onEvent(listOf(up(x = 40f)))
    assertEquals(listOf("press"), handler.calls)
  }

  @Test
  fun claimedTapWithinSlopConsumesTheUp() {
    val handler = Handler(claim = true)
    val session = session(handler)
    session.onEvent(listOf(down()))
    assertEquals(setOf(1L), session.onEvent(listOf(up())))
    assertEquals(listOf("press", "tap1"), handler.calls)
  }

  @Test
  fun secondConsumedTapWithinTimeoutAndRadiusCountsTwo() {
    val handler = Handler(consumeTap = true)
    val session = session(handler)
    session.onEvent(listOf(down(time = 0)))
    session.onEvent(listOf(up(time = 50)))
    session.onEvent(listOf(down(x = 10f, time = 200)))
    session.onEvent(listOf(up(x = 10f, time = 250)))
    session.onEvent(listOf(down(time = 300)))
    session.onEvent(listOf(up(time = 350)))
    assertEquals(listOf("press", "tap1", "press", "tap2", "press", "tap1"), handler.calls)
  }

  @Test
  fun tapsTooLateTooFarOrOfAnotherTypeCountOne() {
    val handler = Handler(consumeTap = true)
    val session = session(handler)
    session.onEvent(listOf(down(time = 0)))
    session.onEvent(listOf(up(time = 50)))
    session.onEvent(listOf(down(time = 400)))
    session.onEvent(listOf(up(time = 450)))
    session.onEvent(listOf(down(x = 60f, time = 500)))
    session.onEvent(listOf(up(x = 60f, time = 550)))
    session.onEvent(listOf(down(x = 60f, time = 600, type = PointerType.Mouse)))
    session.onEvent(listOf(up(x = 60f, time = 650, type = PointerType.Mouse)))
    assertEquals(
      listOf("press", "tap1", "press", "tap1", "press", "tap1", "press", "tap1"),
      handler.calls,
    )
  }

  @Test
  fun unconsumedTapDoesNotStartADoubleTap() {
    val handler = Handler()
    val session = session(handler)
    session.onEvent(listOf(down(time = 0)))
    session.onEvent(listOf(up(time = 50)))
    handler.consumeTap = true
    session.onEvent(listOf(down(time = 100)))
    session.onEvent(listOf(up(time = 150)))
    assertEquals(listOf("press", "tap1", "press", "tap1"), handler.calls)
  }

  @Test
  fun longPressSwallowsTheClaimedPointer() {
    val handler = Handler(claim = true)
    val session = session(handler)
    session.onEvent(listOf(down()))
    assertTrue(session.longPressPending)
    session.onEvent(listOf(move(x = 5f)))
    assertTrue(session.longPress())
    assertEquals(5f, handler.lastSample?.screen?.x?.value)
    assertFalse(session.longPressPending)
    assertFalse(session.isClaimed)
    assertEquals(setOf(1L), session.onEvent(listOf(move(x = 50f))))
    assertEquals(setOf(1L), session.onEvent(listOf(up(x = 50f))))
    assertEquals(listOf("press", "longPress"), handler.calls)
    session.onEvent(listOf(down()))
    assertEquals(listOf("press", "longPress", "press"), handler.calls)
  }

  @Test
  fun longPressIsNotPendingForMouseUnclaimedOrDraggedPointers() {
    val handler = Handler()
    val session = session(handler)
    session.onEvent(listOf(down()))
    assertFalse(session.longPressPending)
    assertFalse(session.longPress())
    session.onEvent(listOf(up()))

    handler.claim = true
    session.onEvent(listOf(down(type = PointerType.Mouse)))
    assertFalse(session.longPressPending)
    session.onEvent(listOf(up(type = PointerType.Mouse)))

    session.onEvent(listOf(down()))
    session.onEvent(listOf(move(x = 40f)))
    assertFalse(session.longPressPending)
  }

  @Test
  fun secondPointerDuringClaimedDragCommitsAtTheLastSample() {
    val handler = Handler(claim = true)
    val session = session(handler)
    session.onEvent(listOf(down()))
    session.onEvent(listOf(move(x = 40f)))
    val consumed = session.onEvent(listOf(move(x = 40f), down(id = 2, x = 100f)))
    assertEquals(setOf(1L, 2L), consumed)
    assertEquals(listOf("press", "drag", "drag", "release"), handler.calls)
    assertEquals(40f, handler.lastSample?.screen?.x?.value)
    assertEquals(setOf(1L, 2L), session.onEvent(listOf(move(x = 60f), move(id = 2, x = 120f))))
    assertEquals(setOf(1L, 2L), session.onEvent(listOf(up(x = 60f), up(id = 2, x = 120f))))
    assertEquals(listOf("press", "drag", "drag", "release"), handler.calls)
  }

  @Test
  fun secondPointerDuringClaimedPressCancels() {
    val handler = Handler(claim = true)
    val session = session(handler)
    session.onEvent(listOf(down()))
    assertEquals(setOf(1L, 2L), session.onEvent(listOf(move(x = 2f), down(id = 2, x = 100f))))
    assertEquals(listOf("press", "cancel"), handler.calls)
    assertEquals(setOf(1L, 2L), session.onEvent(listOf(up(x = 2f), move(id = 2, x = 100f))))
    assertEquals(setOf(2L), session.onEvent(listOf(up(id = 2, x = 100f))))
    session.onEvent(listOf(down(x = 5f)))
    assertEquals(listOf("press", "cancel", "press"), handler.calls)
  }

  @Test
  fun secondPointerDuringUnclaimedPressDropsTheTapAndIsNotDelivered() {
    val handler = Handler()
    val session = session(handler)
    session.onEvent(listOf(down()))
    assertEquals(emptySet(), session.onEvent(listOf(move(x = 2f), down(id = 2, x = 100f))))
    assertEquals(emptySet(), session.onEvent(listOf(up(x = 2f), up(id = 2, x = 100f))))
    assertEquals(listOf("press"), handler.calls)
  }

  @Test
  fun cancelDeliversCancelForAClaimedPointerAndSwallowsUntilLift() {
    val handler = Handler(claim = true)
    val session = session(handler)
    session.onEvent(listOf(down()))
    assertTrue(session.cancel())
    assertFalse(session.cancel())
    assertEquals(listOf("press", "cancel"), handler.calls)
    assertEquals(setOf(1L), session.onEvent(listOf(move(x = 40f))))
    assertEquals(setOf(1L), session.onEvent(listOf(up(x = 40f))))
    assertEquals(listOf("press", "cancel"), handler.calls)
    session.onEvent(listOf(down()))
    assertEquals(listOf("press", "cancel", "press"), handler.calls)
  }

  @Test
  fun cancelDoesNothingForAnUnclaimedPointer() {
    val handler = Handler()
    val session = session(handler)
    session.onEvent(listOf(down()))
    assertFalse(session.cancel())
    session.onEvent(listOf(up()))
    assertEquals(listOf("press", "tap1"), handler.calls)
  }

  @Test
  fun resetCancelsAndForgetsContacts() {
    val handler = Handler(claim = true)
    val session = session(handler)
    session.onEvent(listOf(down()))
    session.reset()
    assertEquals(listOf("press", "cancel"), handler.calls)
    assertEquals(emptySet(), session.onEvent(listOf(up())))
    session.onEvent(listOf(down(id = 3)))
    assertEquals(listOf("press", "cancel", "press"), handler.calls)
  }

  @Test
  fun rejectedDragCancels() {
    val handler = Handler(claim = true)
    val session = session(handler)
    handler.dragResult = false
    session.onEvent(listOf(down()))
    session.onEvent(listOf(move(x = 40f)))
    assertEquals(listOf("press", "drag", "cancel"), handler.calls)
    assertSame(handler.steps[0], handler.steps[2])
    assertEquals(setOf(1L), session.onEvent(listOf(up(x = 40f))))
    assertEquals(listOf("press", "drag", "cancel"), handler.calls)
  }

  @Test
  fun eachPressGetsItsOwnStep() {
    val handler = Handler(claim = true)
    val session = session(handler)
    session.onEvent(listOf(down()))
    session.onEvent(listOf(up()))
    session.onEvent(listOf(down()))
    session.onEvent(listOf(up()))
    assertTrue(handler.steps[0] !== handler.steps[2])
    assertSame(handler.steps[0], handler.steps[1])
  }
}
