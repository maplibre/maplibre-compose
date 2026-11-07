package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Offset
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CubicBezier
import org.maplibre.compose.interaction.FrictionMomentumBuilder
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.MomentumGlide
import org.maplibre.compose.interaction.MomentumPlanner
import org.maplibre.compose.map.GestureTestFixture

@OptIn(ExperimentalCoroutinesApi::class)
class GestureMomentumTest {
  private val map = GestureTestFixture()

  @AfterTest fun closeMap() = map.close()

  private val pan = GlideDecay.Friction(MomentumDefaults.PanFriction)

  @Test
  fun friction_halves_the_speed_every_half_life_until_the_stop_speed() {
    val glide = assertNotNull(Glide.plan(1000.0, pan))
    val rate = ln(2.0) / 0.14
    assertEquals((1000.0 - 10.0) / rate, glide.distance, 1e-9)
    assertEquals(ln(100.0) / rate, glide.duration, 1e-9)
    assertEquals(0.0, glide.progress(0.0))
    assertEquals(1.0, glide.progress(glide.duration))
    // The glide starts at the release speed, and one half-life later moves at half of it.
    assertEquals(1000.0, glide.distance * glide.speed(0.0), 1e-9)
    assertEquals(500.0, glide.distance * glide.speed(0.14), 1e-9)
    assertEquals(10.0, glide.distance * glide.speed(glide.duration - 1e-9), 1e-6)
    assertEquals((1 - exp(-rate * 0.1)) / 0.99, glide.progress(0.1), 1e-12)
  }

  @Test
  fun friction_limits_the_release_speed_and_the_distance() {
    val capped = assertNotNull(Glide.plan(20_000.0, pan))
    assertEquals(assertNotNull(Glide.plan(10_000.0, pan)).distance, capped.distance, 1e-9)
    assertNull(Glide.plan(199.0, pan))
    assertNull(Glide.plan(0.0, pan))
    assertNull(Glide.plan(Double.NaN, pan))

    // A zoom that would glide farther than 2.5 levels starts slower, so it ends sooner.
    val zoom = GlideDecay.Friction(MomentumDefaults.ZoomFriction)
    val far = assertNotNull(Glide.plan(100.0, zoom))
    assertEquals(2.5, far.distance, 1e-9)
    assertEquals(0.01 + ln(2.0) / 0.14 * 2.5, far.distance * far.speed(0.0), 1e-9)
  }

  @Test
  fun an_eased_glide_follows_its_planned_curve_and_distance() {
    val curve = CubicBezier(0.175, 0.5, 0.35, 1.0)
    val planned = MomentumGlide(120.0, 500.milliseconds, curve)
    val glide = assertNotNull(Glide.plan(800.0, GlideDecay.Ease(planned, 50.0, 100.0)))
    assertEquals(100.0, glide.distance)
    assertEquals(0.5, glide.duration)
    val bezier = UnitBezier(curve)
    for (t in listOf(0.05, 0.1, 0.25, 0.4)) {
      assertEquals(bezier.solve(t / 0.5, 1e-6), glide.progress(t), 1e-9)
    }
    assertEquals(1.0, glide.progress(0.5))
    // The speed integrates to the whole distance.
    val step = 0.5 / 10_000
    val covered = (0 until 10_000).sumOf { glide.speed((it + 0.5) * step) * step }
    assertEquals(1.0, covered, 1e-3)
    assertNull(Glide.plan(40.0, GlideDecay.Ease(planned, 50.0, 100.0)))
  }

  @Test
  fun the_android_fling_planner_follows_androids_spline() {
    // OverScroller: a 1000 dp/s fling travels about 194 dp over about 555 ms.
    val glide = assertNotNull(MomentumPlanner.AndroidFling.plan(1000.0))
    assertEquals(194.3, glide.distance, 0.5)
    assertEquals(555.0, glide.duration.toDouble(DurationUnit.MILLISECONDS), 2.0)
    assertEquals(CubicBezier(0.175, 0.5, 0.35, 1.0), glide.easing)
    val faster = assertNotNull(MomentumPlanner.AndroidFling.plan(4000.0))
    assertTrue(faster.distance > glide.distance && faster.duration > glide.duration)
  }

  @Test
  fun momentum_settings_choose_and_validate_the_decay() {
    val defaults = MapInteractions.Standard.camera.settings
    assertEquals(MomentumDefaults.ZoomFriction, defaults.zoom.momentum.decay)
    assertEquals(MomentumDefaults.BearingFriction, defaults.rotate.momentum.decay)
    assertEquals(MomentumDefaults.PitchFriction, defaults.pitch.momentum.decay)
    assertEquals(PlatformPanDecay, defaults.pan.momentum.decay)

    val planner = MomentumPlanner { MomentumGlide(it / 4, 300.milliseconds, CubicBezier.Linear) }
    val eased = MapInteractions {
      camera {
        pan {
          momentum {
            ease(planner) { maxDistance = 500.0 }
          }
        }
        zoom { momentum { friction { halfLife = 200.milliseconds } } }
      }
    }
    // Ease starts from the minimum speed of the part's current decay.
    assertEquals(
      MomentumDecay.Ease(planner, PlatformPanDecay.minSpeed, 500.0),
      eased.camera.settings.pan.momentum.decay,
    )
    assertEquals(
      MomentumDefaults.ZoomFriction.copy(halfLife = 200.milliseconds),
      eased.camera.settings.zoom.momentum.decay,
    )
    // Friction starts from the part's defaults when the part eases.
    val friction = MapInteractions(eased) { camera { pan { momentum { friction() } } } }
    assertEquals(MomentumDefaults.PanFriction, friction.camera.settings.pan.momentum.decay)

    for (invalid in
      listOf<FrictionMomentumBuilder.() -> Unit>(
        { halfLife = 0.milliseconds },
        { stopSpeed = 0.0 },
        { minSpeed = -1.0 },
        { minSpeed = Double.POSITIVE_INFINITY },
        { maxSpeed = 0.0 },
        { maxDistance = Double.NaN },
      )) {
      assertFailsWith<IllegalArgumentException> {
        MapInteractions { camera { pan { momentum { friction(invalid) } } } }
      }
    }
    assertFailsWith<IllegalArgumentException> {
      MomentumGlide(10.0, 100.milliseconds, CubicBezier(0.5, 1.5, 0.5, 1.0))
    }
  }

  private fun resolve(
    release: ReleaseVelocity?,
    boost: GlideVelocity? = null,
    changed: Set<CameraComponent> = emptySet(),
    settings: CameraSettings = FrictionSettings,
    started: MutableList<CameraComponent> = mutableListOf(),
  ) =
    resolveGestureMomentum(
      settings,
      release,
      boost,
      changed,
      {
        started += it
        true
      },
      1f,
    )

  @Test
  fun a_release_moves_only_parts_that_clear_their_minimum_speed() {
    val started = mutableListOf<CameraComponent>()
    val momentum =
      assertNotNull(
        resolve(
          ReleaseVelocity(pan = Offset(150f, 0f), zoom = 1.0, bearing = 30.0, pitch = -20.0),
          started = started,
        ),
      )
    assertNull(momentum.pan)
    assertEquals(1.0, momentum.zoom?.velocity)
    assertNull(momentum.bearing)
    assertEquals(-20.0, momentum.pitch?.velocity)
    assertEquals(listOf(CameraComponent.Zoom, CameraComponent.Pitch), started)

    val disabled = MapInteractions {
      camera { zoom { momentum { enabled = false } } }
    }
      .camera
      .settings
    assertNull(resolve(ReleaseVelocity(zoom = 1.0), settings = disabled))
  }

  @Test
  fun only_the_parts_that_input_changed_settle() {
    assertNull(resolve(null))
    val rotated = assertNotNull(resolve(null, changed = setOf(CameraComponent.Rotate)))
    assertEquals(MomentumDefaults.BearingSnapping, rotated.bearing?.snap)
    assertNull(rotated.bearing?.velocity)
    assertNull(rotated.zoom)
    // Zoom and pitch settle only when snapping is on.
    assertNull(resolve(null, changed = setOf(CameraComponent.Zoom, CameraComponent.Pitch)))
  }

  @Test
  fun a_cancelled_glide_adds_to_a_release_the_same_way() {
    val boost = GlideVelocity(panX = 600.0, zoom = -2.0, bearing = 90.0)
    val same =
      assertNotNull(
        resolve(ReleaseVelocity(pan = Offset(400f, 300f), zoom = -1.0, bearing = 100.0), boost),
      )
    assertEquals(1000.0, assertNotNull(same.pan).velocityX)
    assertEquals(300.0, assertNotNull(same.pan).velocityY)
    assertEquals(-3.0, same.zoom?.velocity)
    assertEquals(190.0, same.bearing?.velocity)

    val opposite =
      assertNotNull(
        resolve(ReleaseVelocity(pan = Offset(-400f, 0f), zoom = 1.0, bearing = -100.0), boost),
      )
    assertEquals(-400.0, assertNotNull(opposite.pan).velocityX)
    assertEquals(1.0, opposite.zoom?.velocity)
    assertEquals(-100.0, opposite.bearing?.velocity)

    // A release too slow to glide on its own stays put.
    assertNull(resolve(ReleaseVelocity(pan = Offset(100f, 0f)), boost))
  }

  @Test
  fun an_eased_part_plans_its_glide_from_the_release_speed() {
    val planner = MomentumPlanner { MomentumGlide(it / 4, 300.milliseconds, CubicBezier.Linear) }
    val settings = MapInteractions {
      camera { pan { momentum { ease(planner) { minSpeed = 10.0 } } } }
    }
      .camera
      .settings
    val momentum =
      assertNotNull(resolve(ReleaseVelocity(pan = Offset(300f, 400f)), settings = settings))
    val decay = assertNotNull(momentum.pan).decay as GlideDecay.Ease
    assertEquals(125.0, decay.glide.distance)
    val declined = MapInteractions {
      camera { pan { momentum { ease({ null }) { minSpeed = 10.0 } } } }
    }
    assertNull(
      resolve(ReleaseVelocity(pan = Offset(300f, 400f)), settings = declined.camera.settings),
    )
  }

  @Test
  fun the_frame_loop_glides_in_bounded_steps_then_settles() = runTest {
    val clock = BroadcastFrameClock()
    val scope = CoroutineScope(backgroundScope.coroutineContext + clock)
    val target = map.target
    target.lastCameraPosition = CameraPosition(bearing = 2.0)
    val token = target.onGestureStarted()
    val momentum =
      GestureMomentum(
        pan = PanMomentumPart(3000.0, -4000.0, pan),
        bearing =
          AnchoredMomentumPart(
            null,
            GlideDecay.Friction(MomentumDefaults.BearingFriction),
            null,
            MomentumDefaults.BearingSnapping,
          ),
      )
    val glide = assertNotNull(Glide.plan(5000.0, pan))
    val run = scope.launch { target.runMomentum(momentum, token) }
    runCurrent()
    var nanos = 0L
    while (run.isActive && nanos < 5_000_000_000) {
      clock.sendFrame(nanos)
      runCurrent()
      nanos += 16_000_000
    }
    assertTrue(run.isCompleted)
    assertTrue(target.moveCalls.all { hypot(it.x, it.y) <= 16.0 + 1e-3 })
    assertEquals(glide.distance * 0.6, target.moveCalls.sumOf { it.x.toDouble() }, 1e-2)
    assertEquals(-glide.distance * 0.8, target.moveCalls.sumOf { it.y.toDouble() }, 1e-2)
    // The bearing eased from 2° to north.
    assertEquals(-2.0, target.rotateCalls.sumOf { it.bearingDelta }, 1e-9)
    assertEquals(0.0, target.lastCameraPosition.bearing, 1e-9)
  }

  @Test
  fun a_glide_that_newer_input_stops_carries_on_into_its_release() = runTest {
    val clock = BroadcastFrameClock()
    val scope = CoroutineScope(backgroundScope.coroutineContext + clock)
    val target = map.target
    target.updateConfiguration(MapInteractions { camera { pan { momentum { friction() } } } })
    val gliding = GestureInputSession(scope, target)
    target.moveBy(1.0, 0.0, gliding.token)
    gliding.end(ReleaseVelocity(pan = Offset(2000f, 0f)))
    runCurrent()
    clock.sendFrame(0)
    runCurrent()
    clock.sendFrame(100_000_000)
    runCurrent()
    assertTrue(target.moveCalls.size > 1)

    val newer = GestureInputSession(scope, target)
    runCurrent()
    val momentum = assertNotNull(newer.token.momentum(ReleaseVelocity(pan = Offset(500f, 0f))))
    val stillMoving = 2000.0 * exp(-ln(2.0) / 0.14 * 0.1)
    assertEquals(500.0 + stillMoving, assertNotNull(momentum.pan).velocityX, 1.0)
    // The boost is taken once.
    assertEquals(
      500.0,
      assertNotNull(newer.token.momentum(ReleaseVelocity(pan = Offset(500f, 0f)))?.pan).velocityX,
    )
    newer.cancel()
  }

  private companion object {
    /** The documented defaults, with friction pans on every platform. */
    val FrictionSettings = MapInteractions {
      camera { pan { momentum { friction() } } }
    }
      .camera
      .settings
  }
}
