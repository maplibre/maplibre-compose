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
import org.maplibre.compose.camera.internal.CameraInputScope
import org.maplibre.compose.camera.internal.inputFitBoundsAwaitingTransition
import org.maplibre.compose.camera.internal.inputPanBy
import org.maplibre.compose.camera.internal.inputRotateAndPitchBy
import org.maplibre.compose.camera.internal.inputScaleBy
import org.maplibre.compose.camera.internal.inputScaleByAwaitingTransition
import org.maplibre.compose.camera.internal.withCameraInput
import org.maplibre.compose.interaction.CameraBuilder
import org.maplibre.compose.interaction.CameraInputOrigin
import org.maplibre.compose.interaction.CameraInputStart
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.internal.CameraComponent
import org.maplibre.compose.interaction.internal.CameraConfiguration
import org.maplibre.compose.interaction.internal.ClickPath
import org.maplibre.compose.interaction.internal.GestureContinuation
import org.maplibre.compose.interaction.internal.GestureInputSession
import org.maplibre.compose.interaction.internal.GesturePointerSample
import org.maplibre.compose.interaction.internal.InteractionSubscriptions
import org.maplibre.compose.interaction.internal.TapDispatcher
import org.maplibre.compose.interaction.internal.TapFamily
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.RecordingGestureTarget
import org.maplibre.compose.map.mapRuntimeForTest
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalCoroutinesApi::class)
class CameraInputTest {
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
      val continuation = GestureContinuation(backgroundScope)
      val dispatcher =
        TapDispatcher(
          backgroundScope,
          clicks,
          InteractionSubscriptions(MapInteractions.Standard),
        ) {
          MapInteractions.Standard
        }
      fun dispatch(id: Long, generation: Long) {
        dispatcher.dispatch(
          checkNotNull(dispatcher.capture(TapFamily.DoubleTap)),
          GesturePointerSample(id, 10, DpOffset.Zero, null, emptySet(), emptySet(), emptySet()),
        ) {
          continuation.launchTapTransition(target, generation) { token ->
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
  fun acquisition_requires_a_current_presentable_viewport() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Empty)
    assertFailsWith<IllegalStateException> { state.withCameraInput {} }
    state.close()
    assertFailsWith<IllegalStateException> { state.withCameraInput {} }
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun normal_completion_seals_enqueues_and_waits_for_the_ordered_fence() =
    cameraTest { state, target ->
      lateinit var retained: CameraInputScope
      val work = launch {
        state.withCameraInput {
          retained = this
          panBy(10.0, 0.0)
          panBy(20.0, 0.0)
        }
      }
      runCurrent()
      assertFalse(work.isCompleted)
      assertTrue(target.moveCalls.isEmpty())
      assertFailsWith<IllegalStateException> { retained.panBy(30.0, 0.0) }
      target.drain()
      runCurrent()
      assertTrue(work.isCompleted)
      assertEquals(listOf(10.0, 20.0), target.moveCalls.map { it.x.toDouble() })
      assertFailsWith<IllegalStateException> { retained.panBy(30.0, 0.0) }
    }

  @Test
  fun takeover_drops_queued_work_and_returns_to_the_outer_input_loop() =
    cameraTest { state, target ->
      lateinit var firstScope: CameraInputScope
      var returned = false
      val first = launch {
        state.withCameraInput {
          firstScope = this
          panBy(10.0, 0.0)
          awaitCancellation()
        }
        returned = true
      }
      runCurrent()
      lateinit var secondScope: CameraInputScope
      val second = launch {
        state.withCameraInput {
          secondScope = this
          panBy(20.0, 0.0)
          awaitCancellation()
        }
      }
      runCurrent()
      assertFailsWith<IllegalStateException> { firstScope.panBy(30.0, 0.0) }
      target.drain()
      runCurrent()
      assertTrue(returned)
      assertFalse(first.isCancelled)
      assertFalse(second.isCompleted)
      assertEquals(listOf(20.0), target.moveCalls.map { it.x.toDouble() })
      secondScope.panBy(40.0, 0.0)
      target.drain()
      assertEquals(listOf(20.0, 40.0), target.moveCalls.map { it.x.toDouble() })
      second.cancel()
      runCurrent()
      target.drain()
      runCurrent()
      assertTrue(second.isCancelled)
    }

  @Test
  fun caller_cancellation_revokes_accepted_commands_and_still_cancels_the_caller() =
    cameraTest { state, target ->
      val work = launch {
        state.withCameraInput {
          panBy(10.0, 0.0)
          awaitCancellation()
        }
      }
      runCurrent()
      work.cancel()
      runCurrent()
      target.drain()
      runCurrent()
      assertTrue(work.isCancelled)
      assertTrue(work.isCompleted)
      assertTrue(target.moveCalls.isEmpty())
    }

  @Test
  fun cancelling_a_caller_waiting_on_a_normal_fence_revokes_its_queued_commands() =
    cameraTest { state, target ->
      val work = launch { state.withCameraInput { panBy(10.0, 0.0) } }
      runCurrent()
      assertFalse(work.isCompleted)
      work.cancel()
      // Drain before the child's finally resumes: execution must check the registered job too.
      target.drain()
      runCurrent()
      target.drain()
      runCurrent()
      assertTrue(work.isCompleted)
      assertTrue(work.isCancelled)
      assertTrue(target.moveCalls.isEmpty())
    }

  @Test
  fun block_failure_propagates_after_cleanup_and_does_not_drain_camera_work() =
    cameraTest { state, target ->
      var failure: Throwable? = null
      val work = launch {
        failure =
          runCatching {
            state.withCameraInput {
              panBy(10.0, 0.0)
              error("tool failed")
            }
          }
            .exceptionOrNull()
      }
      runCurrent()
      assertFalse(work.isCompleted)
      target.drain()
      runCurrent()
      assertEquals("tool failed", failure?.message)
      assertTrue(target.moveCalls.isEmpty())
    }

  @Test
  fun public_mutation_from_the_block_invalidates_old_authority_immediately() =
    cameraTest { state, target ->
      var rejected = false
      val work = launch {
        state.withCameraInput {
          panBy(10.0, 0.0)
          state.setCameraPosition(CameraPosition(zoom = 6.0))
          assertFailsWith<IllegalStateException> { panBy(20.0, 0.0) }
          rejected = true
        }
      }
      runCurrent()
      target.drain()
      runCurrent()
      assertTrue(rejected)
      assertTrue(work.isCompleted)
      assertTrue(target.moveCalls.isEmpty())
    }

  @Test
  fun detach_rejects_the_scope_before_asynchronous_cleanup() = cameraTest { state, target ->
    val work = launch {
      state.withCameraInput {
        panBy(10.0, 0.0)
        state.invalidatePresentation(target)
        assertFailsWith<IllegalStateException> { panBy(20.0, 0.0) }
      }
    }
    runCurrent()
    target.drain()
    runCurrent()
    assertTrue(work.isCompleted)
    assertTrue(target.moveCalls.isEmpty())
  }

  @Test
  fun same_state_nesting_is_rejected_before_it_can_cancel_the_parent() =
    cameraTest { state, target ->
      val work = launch {
        state.withCameraInput {
          assertFailsWith<IllegalStateException> { state.withCameraInput {} }
          panBy(10.0, 0.0)
        }
      }
      runCurrent()
      target.drain()
      runCurrent()
      assertTrue(work.isCompleted)
      assertFalse(work.isCancelled)
      assertEquals(listOf(10.0), target.moveCalls.map { it.x.toDouble() })
    }

  @Test
  fun different_states_can_nest_but_a_to_b_to_a_cannot() = cameraTest { state, target ->
    val other = state.runtime.createMapState(BaseStyle.Empty)
    val otherTarget = RecordingGestureTarget(other, deferred = true)
    val work = launch {
      state.withCameraInput {
        panBy(10.0, 0.0)
        other.withCameraInput {
          assertFailsWith<IllegalStateException> { state.withCameraInput {} }
          panBy(20.0, 0.0)
        }
        panBy(30.0, 0.0)
      }
    }
    runCurrent()
    otherTarget.drain()
    runCurrent()
    target.drain()
    runCurrent()
    assertTrue(work.isCompleted)
    assertEquals(listOf(10.0, 30.0), target.moveCalls.map { it.x.toDouble() })
    assertEquals(listOf(20.0), otherTarget.moveCalls.map { it.x.toDouble() })
    other.close()
    other.awaitClosed()
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
  fun a_later_camera_owner_invalidates_a_programmatic_guard_without_a_gesture_being_active() =
    cameraTest { state, target ->
      val first = state.gestureAuthority.beginProgrammatic()
      assertTrue(first.isValid())
      val second = state.gestureAuthority.beginProgrammatic()
      assertFalse(first.isValid())
      assertTrue(second.isValid())
      val token = target.onGestureStarted()
      assertFalse(second.isValid())
      target.onGestureEnded(token)
      target.drain()
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
      val input = GestureInputSession(this, target, origin = CameraInputOrigin.Transform)
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
  fun component_restarts_share_session_and_use_current_observer_without_cancelling_input() =
    cameraTest { state, target ->
      val starts = mutableListOf<CameraInputStart>()
      val initial =
        CameraBuilder(CameraConfiguration()).apply { pan { onStart { starts += it } } }.build()
      state.gestureAuthority.updateConfiguration(initial)
      val input = GestureInputSession(this, target, origin = CameraInputOrigin.Transform)
      target.inputPanBy(1.0, 0.0, gestureToken = input.token)
      target.inputPanBy(2.0, 0.0, gestureToken = input.token)
      var replacement = 0
      state.gestureAuthority.updateConfiguration(
        initial.copy(
          pan =
            initial.pan.copy(
              onStart = {
                starts += it
                replacement++
              }
            )
        )
      )
      assertTrue(input.token.acceptsCommands)
      input.token.rearm(CameraComponent.Pan)
      target.inputPanBy(3.0, 0.0, gestureToken = input.token)
      assertEquals(1, replacement)
      assertEquals(
        listOf(
          CameraInputStart(input.token.value, CameraInputOrigin.Transform),
          CameraInputStart(input.token.value, CameraInputOrigin.Transform),
        ),
        starts,
      )
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
  fun external_disabled_commands_still_validate_and_policy_changes_revoke_queued_work() =
    cameraTest { state, target ->
      state.gestureAuthority.updateConfiguration(
        CameraBuilder(CameraConfiguration())
          .apply {
            pan { enabled = false }
          }
          .build()
      )
      val input = launch {
        state.withCameraInput {
          assertFailsWith<IllegalArgumentException> { panBy(Double.NaN, 0.0) }
          panBy(10.0, 0.0)
          scaleBy(2.0)
          state.gestureAuthority.updateConfiguration(
            CameraBuilder(CameraConfiguration())
              .apply {
                zoom { enabled = false }
              }
              .build()
          )
          assertFailsWith<IllegalStateException> { panBy(0.0, 0.0) }
        }
      }
      runCurrent()
      target.drain()
      runCurrent()
      assertTrue(input.isCompleted)
      assertTrue(target.moveCalls.isEmpty())
      assertTrue(target.scaleCalls.isEmpty())
    }

  @Test
  fun fit_bounds_requires_pan_and_zoom_and_does_not_emit_component_starts() =
    cameraTest { state, target ->
      var starts = 0
      val fit = BoxZoomFit(BoundingBox(Position(0.0, 0.0), Position(1.0, 1.0)), 0.0, 0.0)
      for ((pan, zoom) in listOf(false to true, true to false, true to true)) {
        state.gestureAuthority.updateConfiguration(
          CameraBuilder(CameraConfiguration())
            .apply {
              pan {
                enabled = pan
                onStart { starts++ }
              }
              zoom {
                enabled = zoom
                onStart { starts++ }
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
          if (pan && zoom) listOf(fit) else emptyList(),
          target.fitCalls.map { it.first },
        )
      }
      assertEquals(0, starts)
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
