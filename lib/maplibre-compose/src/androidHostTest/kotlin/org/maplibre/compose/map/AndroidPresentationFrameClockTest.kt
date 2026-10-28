package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidPresentationFrameClockTest {
  @Test
  fun fallback_delay_is_two_frames_of_the_display_rate() {
    assertEquals(16L, fallbackFrameDelayMs(120f))
    assertEquals(22L, fallbackFrameDelayMs(90f))
    assertEquals(33L, fallbackFrameDelayMs(60f))
    assertEquals(16L, fallbackFrameDelayMs(240f))
    assertEquals(48L, fallbackFrameDelayMs(10f))
  }

  @Test
  fun fallback_delay_treats_invalid_rates_as_60_hz() {
    assertEquals(33L, fallbackFrameDelayMs(Float.NaN))
    assertEquals(33L, fallbackFrameDelayMs(0f))
    assertEquals(33L, fallbackFrameDelayMs(-60f))
    assertEquals(33L, fallbackFrameDelayMs(Float.POSITIVE_INFINITY))
  }

  @Test
  fun mixed_vsync_and_handler_times_stay_monotonic() {
    val afterVsync = monotonicFrameTimeNanos(0L, 1_000L)
    assertEquals(1_000L, afterVsync)
    assertEquals(1_001L, monotonicFrameTimeNanos(afterVsync, 500L))
  }

  @Test
  fun first_source_delivers_once_and_cancels_the_other() {
    var vsync: ((Long) -> Unit)? = null
    var fallback: ((Long) -> Unit)? = null
    var cancelledVsync = 0
    var cancelledFallback = 0
    val delivered = mutableListOf<Long>()

    scheduleFirstWinsFrame(
      postVsync = { onFrame ->
        vsync = onFrame
        ({
          cancelledVsync += 1
          vsync = null
        })
      },
      postFallback = { onFrame ->
        fallback = onFrame
        ({
          cancelledFallback += 1
          fallback = null
        })
      },
      deliver = { delivered += it },
    )

    vsync!!(10L)
    fallback?.invoke(20L)
    assertEquals(listOf(10L), delivered)
    assertEquals(1, cancelledVsync)
    assertEquals(1, cancelledFallback)
    assertTrue(vsync == null && fallback == null)
  }

  @Test
  fun silent_vsync_falls_back_to_the_handler_tick() {
    var fallback: ((Long) -> Unit)? = null
    val delivered = mutableListOf<Long>()

    scheduleFirstWinsFrame(
      postVsync = { _ -> {} },
      postFallback = { onFrame ->
        fallback = onFrame
        ({ fallback = null })
      },
      deliver = { delivered += it },
    )

    fallback!!(30L)
    assertEquals(listOf(30L), delivered)
    assertEquals(null, fallback)
  }
}
