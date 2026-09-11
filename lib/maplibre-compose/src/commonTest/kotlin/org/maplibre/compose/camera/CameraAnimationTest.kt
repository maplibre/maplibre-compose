package org.maplibre.compose.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds
import org.maplibre.spatialk.geojson.Position

class CameraAnimationTest {

  /** Only a speed-paced flight with no ground to cover falls back to an ease. */
  @Test
  fun a_speed_paced_flight_without_a_path_becomes_an_ease() {
    val flight = CameraAnimation.Fly(easing = CubicBezier.Linear)
    val turned = START.copy(bearing = 90.0, tilt = 30.0)

    assertEquals(CameraAnimation.Ease(easing = CubicBezier.Linear), flight.forPath(START, turned))
    assertEquals(CameraAnimation.Ease(easing = CubicBezier.Linear), flight.forPath(START, START))
    assertSame(flight, flight.forPath(START, START.copy(zoom = 5.0)))
    assertSame(flight, flight.forPath(START, START.copy(target = Position(1.0, 0.0))))
  }

  @Test
  fun a_timed_flight_and_an_ease_keep_their_animation() {
    val timed = CameraAnimation.Fly(1.seconds)
    val ease = CameraAnimation.Ease()

    assertSame(timed, timed.forPath(START, START))
    assertSame(ease, ease.forPath(START, START))
  }

  /** The same longitude in another world copy is no path to fly. */
  @Test
  fun a_flight_to_the_same_longitude_in_another_world_copy_has_no_path() {
    val flight = CameraAnimation.Fly()
    val wrapped = START.copy(target = Position(START.target.longitude + 360.0, 0.0))

    assertEquals(CameraAnimation.Ease(), flight.forPath(START, wrapped))
  }

  private companion object {
    val START = CameraPosition(target = Position(170.0, 0.0), zoom = 3.0)
  }
}
