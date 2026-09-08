package org.maplibre.compose.camera

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.camera.internal.BoxZoomFit
import org.maplibre.compose.camera.internal.inputFitBoundsAwaitingTransition
import org.maplibre.compose.camera.internal.inputPanBy
import org.maplibre.compose.camera.internal.inputRotateAndPitchBy
import org.maplibre.compose.camera.internal.inputScaleBy
import org.maplibre.compose.camera.internal.inputScaleByAwaitingTransition
import org.maplibre.compose.interaction.BearingTargets
import org.maplibre.compose.interaction.CameraBuilder
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.HapticEmphasis
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.internal.CameraComponent
import org.maplibre.compose.interaction.internal.CameraConfiguration
import org.maplibre.compose.interaction.internal.ClickPath
import org.maplibre.compose.interaction.internal.GestureInputSession
import org.maplibre.compose.interaction.internal.GesturePointerSample
import org.maplibre.compose.interaction.internal.InputConfiguration
import org.maplibre.compose.interaction.internal.TapDispatcher
import org.maplibre.compose.interaction.internal.TapFamily
import org.maplibre.compose.interaction.internal.launchTapTransition
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.RecordingGestureTarget
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalCoroutinesApi::class)
class CameraInputTest {
  @Test
  fun haptics_survive_new_samples_but_pending_feedback_is_cancelled_on_takeover() =
    cameraTest { _, target ->
      val options = MapInteractions {
        camera { rotate { haptics { notch(BearingTargets.at(0.0)) } } }
      }
      target.updateConfiguration(options)
      val ticks = mutableListOf<HapticEmphasis>()
      val input = GestureInputSession(this, target, onHaptic = { ticks += it })
      runCurrent()
      target.observeInput()
      input.token.reportRotation(355.0, 5.0)
      runCurrent()
      assertEquals(listOf(HapticEmphasis.Standard), ticks)
      input.cancel()
      target.drain()
      runCurrent()

      ticks.clear()
      val cancelled = GestureInputSession(this, target, onHaptic = { ticks += it })
      cancelled.token.reportRotation(355.0, 5.0)
      target.interruptCamera()
      runCurrent()
      target.drain()
      assertTrue(ticks.isEmpty())
    }

  @Test
  fun newer_input_preserves_queued_click_delivery_but_rejects_its_camera_fallthrough() =
    cameraTest { _, target ->
      val query = CompletableDeferred<Unit>()
      var layers = 0
      val clicks = { _: TapFamily ->
        ClickPath({ true }) {
          query.await()
          layers++
          ClickResult.Pass
        }
      }
      val dispatcher =
        TapDispatcher(
          backgroundScope,
          clicks,
          { false },
        ) {
          InputConfiguration.Standard
        }
      fun dispatch(id: Long, generation: Long) {
        dispatcher.dispatch(
          TapFamily.DoubleTap,
          GesturePointerSample(id, DpOffset.Zero, null, emptySet(), emptySet(), emptySet()),
        ) {
          launchTapTransition(backgroundScope, target, generation) { token ->
            inputPanBy(10.0, 0.0, gestureToken = token)
          }
        }
      }
      dispatch(1, target.observeInput())
      runCurrent()
      val latest = target.observeInput()
      query.complete(Unit)
      runCurrent()
      target.drain()
      assertEquals(1, layers)
      assertTrue(target.moveCalls.isEmpty())
      dispatch(2, latest)
      runCurrent()
      target.drain()
      runCurrent()
      assertEquals(listOf(10.0), target.moveCalls.map { it.x.toDouble() })
      assertEquals(2, layers)
    }

  @Test
  fun delayed_acquisition_checks_input_generation_without_revoking_a_newer_owner() =
    cameraTest { state, target ->
      val captured = state.gestureAuthority.observeInput()
      val session = GestureInputSession(this, target)
      assertEquals(null, state.gestureAuthority.acquireIfCurrent(target, captured))
      assertTrue(session.token.acceptsCommands)
      session.end()
      target.drain()
      runCurrent()
    }

  @Test
  fun input_without_an_active_camera_session_invalidates_delayed_acquisition() =
    cameraTest { state, target ->
      val captured = state.gestureAuthority.observeInput()
      state.gestureAuthority.observeInput()
      assertEquals(null, state.gestureAuthority.acquireIfCurrent(target, captured))
      val current = state.gestureAuthority.generation
      val acquired = checkNotNull(state.gestureAuthority.acquireIfCurrent(target, current))
      assertTrue(acquired.acceptsCommands)
      target.onGestureEnded(acquired)
      target.drain()
    }

  @Test
  fun input_session_keeps_accepted_commands_alive_through_normal_completion() =
    cameraTest { _, target ->
      val session = GestureInputSession(this, target)
      target.inputPanBy(10.0, 0.0, gestureToken = session.token)
      session.end()
      assertFalse(session.token.acceptsCommands)
      assertTrue(session.token.canExecute)
      assertTrue(session.scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
      target.drain()
      runCurrent()
      assertEquals(listOf(10.0), target.moveCalls.map { it.x.toDouble() })
      assertTrue(session.scope.coroutineContext[kotlinx.coroutines.Job]!!.isCompleted)
    }

  @Test
  fun released_input_finishes_response_work_unless_new_input_interrupts_it() =
    cameraTest { _, target ->
      for (interrupt in listOf(false, true)) {
        val releaseResponse = CompletableDeferred<Unit>()
        val session = GestureInputSession(this, target)
        session.scope.launch {
          releaseResponse.await()
          target.inputPanBy(10.0, 0.0, gestureToken = session.token)
        }
        session.end()
        runCurrent()
        target.drain()
        assertTrue(target.moveCalls.isEmpty())

        if (interrupt) target.interruptCamera()
        releaseResponse.complete(Unit)
        runCurrent()
        target.drain()
        runCurrent()
        assertEquals(
          if (interrupt) emptyList() else listOf(10.0),
          target.moveCalls.map { it.x.toDouble() },
        )
        target.moveCalls.clear()
      }
    }

  @Test
  fun input_takeover_cancels_continuation_and_dispatches_observation_outside_the_owner_call() =
    cameraTest { state, target ->
      var insideOwnerCall = false
      var cancelled = 0
      val session =
        GestureInputSession(this, target) {
          assertFalse(insideOwnerCall)
          cancelled++
        }
      val continuation = session.scope.launch { awaitCancellation() }
      runCurrent()
      target.inputPanBy(10.0, 0.0, gestureToken = session.token)
      insideOwnerCall = true
      state.setCameraPosition(CameraPosition(zoom = 5.0))
      assertFalse(session.token.acceptsCommands)
      assertTrue(continuation.isCancelled)
      assertEquals(0, cancelled)
      insideOwnerCall = false
      runCurrent()
      assertEquals(1, cancelled)
      target.drain()
      assertTrue(target.moveCalls.isEmpty())
    }

  @Test
  fun old_input_cleanup_cannot_close_the_new_session() = cameraTest { _, target ->
    var cancelled = 0
    val first = GestureInputSession(this, target) { cancelled++ }
    target.inputPanBy(10.0, 0.0, gestureToken = first.token)
    val second = GestureInputSession(this, target)
    runCurrent()
    first.cancel()
    assertEquals(1, cancelled)
    assertTrue(second.token.acceptsCommands)
    target.inputPanBy(20.0, 0.0, gestureToken = second.token)
    second.end()
    target.drain()
    runCurrent()
    assertEquals(listOf(20.0), target.moveCalls.map { it.x.toDouble() })
  }

  @Test
  fun cancelled_camera_callers_leave_the_current_gesture_in_control() =
    cameraTest { state, target ->
      val bounds = BoundingBox(0.0, 0.0, 1.0, 1.0)
      val commands: List<suspend () -> Unit> =
        listOf(
          { state.fitCameraToBounds(bounds) },
          { state.animateCameraPosition(CameraPosition(zoom = 5.0)) },
          { state.animateCameraToBounds(bounds) },
        )
      for (command in commands) {
        val token = target.onGestureStarted()
        val caller = launch {
          currentCoroutineContext().cancel()
          assertFailsWith<CancellationException> { command() }
        }
        runCurrent()
        assertTrue(caller.isCompleted)
        target.inputPanBy(10.0, 0.0, gestureToken = token)
        target.onGestureEnded(token)
        target.drain()
      }
      assertEquals(listOf(10.0, 10.0, 10.0), target.moveCalls.map { it.x.toDouble() })
    }

  @Test
  fun shared_policy_gates_axes_and_preserves_center_anchors_without_synthetic_pan_starts() =
    cameraTest { state, target ->
      val starts = mutableListOf<String>()
      state.gestureAuthority.updateConfiguration(
        CameraBuilder(CameraConfiguration())
          .apply {
            pan {
              enabled = false
              onStart { starts += "pan" }
            }
            zoom { onStart { starts += "zoom" } }
            rotate {
              enabled = false
              onStart { starts += "rotate" }
            }
            tilt { onStart { starts += "tilt" } }
          }
          .build()
      )
      val input = GestureInputSession(this, target)
      target.inputPanBy(10.0, 20.0, gestureToken = input.token)
      target.inputScaleBy(2.0, DpOffset(10.dp, 20.dp), gestureToken = input.token)
      target.inputRotateAndPitchBy(
        10.0,
        5.0,
        anchor = DpOffset(10.dp, 20.dp),
        gestureToken = input.token,
      )
      target.inputScaleByAwaitingTransition(2.0, DpOffset(10.dp, 20.dp), Duration.ZERO, input.token)
      input.end()
      target.drain()
      runCurrent()
      assertTrue(target.moveCalls.isEmpty())
      assertEquals(listOf("zoom", "tilt"), starts)
      assertEquals(listOf(2.0, 2.0), target.scaleCalls.map { it.scale })
      assertEquals(listOf(null, null), target.scaleCalls.map { it.anchor })
      assertEquals(RecordingGestureTarget.RotateCall(0.0, 5.0, null), target.rotateCalls.single())
    }

  @Test
  fun component_restarts_use_current_observer_without_cancelling_input() =
    cameraTest { state, target ->
      var starts = 0
      val initial =
        CameraBuilder(CameraConfiguration()).apply { pan { onStart { starts++ } } }.build()
      state.gestureAuthority.updateConfiguration(initial)
      val input = GestureInputSession(this, target)
      target.inputPanBy(1.0, 0.0, gestureToken = input.token)
      target.inputPanBy(2.0, 0.0, gestureToken = input.token)
      var replacement = 0
      state.gestureAuthority.updateConfiguration(
        CameraBuilder(initial)
          .apply {
            pan {
              onStart {
                starts++
                replacement++
              }
            }
          }
          .build()
      )
      assertTrue(input.token.acceptsCommands)
      input.token.rearm(CameraComponent.Pan)
      target.inputPanBy(3.0, 0.0, gestureToken = input.token)
      assertEquals(1, replacement)
      assertEquals(2, starts)
      input.end()
      target.drain()
      runCurrent()
    }

  @Test
  fun semantic_observer_can_take_camera_authority_before_any_command_is_queued() =
    cameraTest { state, target ->
      state.gestureAuthority.updateConfiguration(
        CameraBuilder(CameraConfiguration())
          .apply {
            pan { onStart { state.setCameraPosition(CameraPosition(zoom = 8.0)) } }
          }
          .build()
      )
      val input = GestureInputSession(this, target)
      target.inputPanBy(10.0, 0.0, gestureToken = input.token)
      target.drain()
      runCurrent()
      assertTrue(target.moveCalls.isEmpty())
      assertFalse(input.token.acceptsCommands)
    }

  @Test
  fun fit_bounds_requires_pan_and_zoom_and_observers_can_prevent_the_fit() =
    cameraTest { state, target ->
      val starts = mutableListOf<CameraComponent>()
      val fit = BoxZoomFit(BoundingBox(Position(0.0, 0.0), Position(1.0, 1.0)), 0.0, 0.0)
      for ((pan, zoom, takeover) in
        listOf(
          Triple(false, true, null),
          Triple(true, false, null),
          Triple(true, true, null),
          Triple(true, true, CameraComponent.Pan),
          Triple(true, true, CameraComponent.Zoom),
        )) {
        starts.clear()
        fun started(component: CameraComponent) {
          starts += component
          if (takeover == component) state.setCameraPosition(CameraPosition(zoom = 8.0))
        }
        state.gestureAuthority.updateConfiguration(
          CameraBuilder(CameraConfiguration())
            .apply {
              pan {
                enabled = pan
                onStart { started(CameraComponent.Pan) }
              }
              zoom {
                enabled = zoom
                onStart { started(CameraComponent.Zoom) }
              }
            }
            .build()
        )
        target.fitCalls.clear()
        val input = GestureInputSession(this, target)
        target.inputFitBoundsAwaitingTransition(fit, Duration.ZERO, input.token)
        input.end()
        target.drain()
        runCurrent()
        assertEquals(
          if (pan && zoom && takeover == null) listOf(fit) else emptyList(),
          target.fitCalls.map { it.first },
        )
        assertEquals(
          when {
            !pan || !zoom -> emptyList()
            takeover == CameraComponent.Pan -> listOf(CameraComponent.Pan)
            else -> listOf(CameraComponent.Pan, CameraComponent.Zoom)
          },
          starts,
        )
      }
    }

  private fun cameraTest(body: suspend TestScope.(MapState, RecordingGestureTarget) -> Unit) =
    runTest {
      val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
      val state = runtime.createMapState(BaseStyle.Empty)
      val target = RecordingGestureTarget(state, deferred = true)
      try {
        body(state, target)
      } finally {
        state.close()
        runCurrent()
        target.drain()
        runCurrent()
        state.awaitClosed()
        runtime.close()
      }
    }
}
