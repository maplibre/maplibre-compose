package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class GestureContinuationTest {
  @Test
  fun finishAfter_emits_the_end_when_the_hold_elapses() = runTest {
    val continuation = GestureContinuation(backgroundScope)
    val ended = mutableListOf<Long>()
    val token = GestureToken(1)
    continuation.finishAfter(this, 200.milliseconds, token) { ended += it.value }
    testScheduler.advanceTimeBy(199)
    testScheduler.runCurrent()
    assertEquals(emptyList(), ended)
    testScheduler.advanceTimeBy(2)
    testScheduler.runCurrent()
    assertEquals(listOf(1L), ended)
  }

  @Test
  fun finish_cancels_the_hold_and_closes_the_token_once() = runTest {
    val continuation = GestureContinuation(backgroundScope)
    val ended = mutableListOf<Long>()
    val token = GestureToken(1)
    continuation.finishAfter(this, 200.milliseconds, token) { ended += it.value }
    continuation.finish { ended += it.value }
    assertEquals(listOf(1L), ended)
    testScheduler.advanceTimeBy(500)
    testScheduler.runCurrent()
    assertEquals(listOf(1L), ended)
  }

  @Test
  fun finish_without_pending_work_does_not_close_a_token() = runTest {
    val continuation = GestureContinuation(backgroundScope)
    continuation.finish { error("there is no token to close") }
  }
}
