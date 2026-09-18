package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

@OptIn(ExperimentalTime::class)
class MapFramePacerTest {
  @Test
  fun frame_clock_drivers_tolerate_dispatch_jitter_at_the_display_cadence() {
    val time = TestTimeSource()
    val clockDriven = MapFramePacer(followsFrameClock = true)
    val timerDriven = MapFramePacer()
    clockDriven.rendered(time.markNow())
    timerDriven.rendered(time.markNow())
    time += 16.milliseconds
    assertEquals(Duration.ZERO, clockDriven.remaining(60))
    assertEquals((1.0 / 60).seconds - 16.milliseconds, timerDriven.remaining(60))
  }

  @Test
  fun deadlines_use_render_start_and_follow_cap_changes() {
    val time = TestTimeSource()
    val pacer = MapFramePacer()
    assertEquals(Duration.ZERO, pacer.remaining(1))
    val start = time.markNow()
    time += 300.milliseconds
    pacer.rendered(start)
    assertEquals(700.milliseconds, pacer.remaining(1))
    assertEquals(Duration.ZERO, pacer.remaining(10))
    assertEquals(Duration.ZERO, pacer.remaining(null))
    time += 1.seconds
    assertEquals(Duration.ZERO, pacer.remaining(1))
  }
}
