package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
  fun resume_cancels_the_hold_and_returns_the_open_token() = runTest {
    val continuation = GestureContinuation(backgroundScope)
    val ended = mutableListOf<Long>()
    val token = GestureToken(1)
    continuation.finishAfter(this, 200.milliseconds, token) { ended += it.value }
    assertEquals(1L, continuation.resume()?.value)
    testScheduler.advanceTimeBy(500)
    testScheduler.runCurrent()
    assertEquals(emptyList(), ended)
  }

  @Test
  fun resume_returns_null_when_no_hold_is_open() = runTest {
    val continuation = GestureContinuation(backgroundScope)
    assertNull(continuation.resume())
  }
}
