package org.maplibre.compose.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.VisibleRegion
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalCoroutinesApi::class)
class GestureCameraTest {
  @Test
  fun newer_input_preserves_queued_click_delivery_but_rejects_its_camera_fallthrough() =
    cameraTest { _, target ->
      val query = CompletableDeferred<Unit>()
      var layers = 0
      val clicks =
        object : MapInteractionTarget {
          override fun capture(family: TapFamily) =
            MapClickPath({ true }) {
              query.await()
              layers++
              ClickResult.Pass
            }
        }
      val continuation = GestureContinuation(backgroundScope)
      val dispatcher = MapTapDispatcher(backgroundScope, clicks) { MapGestures.Standard }
      fun dispatch(id: Long, generation: Long) {
        dispatcher.dispatch(
          TapFamily.DoubleTap,
          GesturePointerSample(id, 10, DpOffset.Zero, null, emptySet(), emptySet(), emptySet()),
        ) {
          continuation.launchDiscreteTransition(
            target,
            {},
            { token -> moveBy(10.0, 0.0, gestureToken = token) },
            generation,
          )
        }
      }
      dispatch(1, target.observeInput())
      runCurrent()
      val latest = target.observeInput()
      query.complete(Unit)
      runCurrent()
      target.drain()
      assertEquals(1, layers)
      assertTrue(target.moves.isEmpty())
      dispatch(2, latest)
      runCurrent()
      target.drain()
      runCurrent()
      assertEquals(listOf(10.0), target.moves)
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
      target.moveBy(10.0, 0.0, gestureToken = session.token)
      session.end()
      assertFalse(session.token.acceptsCommands)
      assertTrue(session.token.canExecute)
      assertTrue(session.scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
      target.drain()
      runCurrent()
      assertEquals(listOf(10.0), target.moves)
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
      target.moveBy(10.0, 0.0, gestureToken = session.token)
      insideOwnerCall = true
      state.setCameraPosition(CameraPosition(zoom = 5.0))
      assertFalse(session.token.acceptsCommands)
      assertTrue(continuation.isCancelled)
      assertEquals(0, cancelled)
      insideOwnerCall = false
      runCurrent()
      assertEquals(1, cancelled)
      target.drain()
      assertTrue(target.moves.isEmpty())
    }

  @Test
  fun old_input_cleanup_cannot_close_the_new_session() = cameraTest { _, target ->
    var cancelled = 0
    val first = GestureInputSession(this, target) { cancelled++ }
    target.moveBy(10.0, 0.0, gestureToken = first.token)
    val second = GestureInputSession(this, target)
    runCurrent()
    first.cancel()
    assertEquals(1, cancelled)
    assertTrue(second.token.acceptsCommands)
    target.moveBy(20.0, 0.0, gestureToken = second.token)
    second.end()
    target.drain()
    runCurrent()
    assertEquals(listOf(20.0), target.moves)
  }

  @Test
  fun acquisition_requires_a_current_presentable_viewport() = runTest {
    val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
    val state = runtime.createMapState(BaseStyle.Empty)
    assertFailsWith<IllegalStateException> { state.gestureCamera.withGesture {} }
    state.close()
    assertFailsWith<IllegalStateException> { state.gestureCamera.withGesture {} }
    state.awaitClosed()
    runtime.close()
  }

  @Test
  fun normal_completion_seals_enqueues_and_waits_for_the_ordered_fence() =
    cameraTest { state, target ->
      lateinit var retained: GestureCameraScope
      val work = launch {
        state.gestureCamera.withGesture {
          retained = this
          moveBy(10.0, 0.0)
          moveBy(20.0, 0.0)
        }
      }
      runCurrent()
      assertFalse(work.isCompleted)
      assertTrue(target.moves.isEmpty())
      assertFailsWith<IllegalStateException> { retained.moveBy(30.0, 0.0) }
      target.drain()
      runCurrent()
      assertTrue(work.isCompleted)
      assertEquals(listOf(10.0, 20.0), target.moves)
      assertFailsWith<IllegalStateException> { retained.moveBy(30.0, 0.0) }
    }

  @Test
  fun takeover_drops_queued_work_and_returns_to_the_outer_input_loop() =
    cameraTest { state, target ->
      lateinit var firstScope: GestureCameraScope
      var returned = false
      val first = launch {
        state.gestureCamera.withGesture {
          firstScope = this
          moveBy(10.0, 0.0)
          awaitCancellation()
        }
        returned = true
      }
      runCurrent()
      lateinit var secondScope: GestureCameraScope
      val second = launch {
        state.gestureCamera.withGesture {
          secondScope = this
          moveBy(20.0, 0.0)
          awaitCancellation()
        }
      }
      runCurrent()
      assertFailsWith<IllegalStateException> { firstScope.moveBy(30.0, 0.0) }
      target.drain()
      runCurrent()
      assertTrue(returned)
      assertFalse(first.isCancelled)
      assertFalse(second.isCompleted)
      assertEquals(listOf(20.0), target.moves)
      secondScope.moveBy(40.0, 0.0)
      target.drain()
      assertEquals(listOf(20.0, 40.0), target.moves)
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
        state.gestureCamera.withGesture {
          moveBy(10.0, 0.0)
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
      assertTrue(target.moves.isEmpty())
    }

  @Test
  fun cancelling_a_caller_waiting_on_a_normal_fence_revokes_its_queued_commands() =
    cameraTest { state, target ->
      val work = launch { state.gestureCamera.withGesture { moveBy(10.0, 0.0) } }
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
      assertTrue(target.moves.isEmpty())
    }

  @Test
  fun block_failure_propagates_after_cleanup_and_does_not_drain_camera_work() =
    cameraTest { state, target ->
      var failure: Throwable? = null
      val work = launch {
        failure =
          runCatching {
            state.gestureCamera.withGesture {
              moveBy(10.0, 0.0)
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
      assertTrue(target.moves.isEmpty())
    }

  @Test
  fun public_mutation_from_the_block_invalidates_old_authority_immediately() =
    cameraTest { state, target ->
      var rejected = false
      val work = launch {
        state.gestureCamera.withGesture {
          moveBy(10.0, 0.0)
          state.setCameraPosition(CameraPosition(zoom = 6.0))
          assertFailsWith<IllegalStateException> { moveBy(20.0, 0.0) }
          rejected = true
        }
      }
      runCurrent()
      target.drain()
      runCurrent()
      assertTrue(rejected)
      assertTrue(work.isCompleted)
      assertTrue(target.moves.isEmpty())
    }

  @Test
  fun detach_rejects_the_scope_before_asynchronous_cleanup() = cameraTest { state, target ->
    val work = launch {
      state.gestureCamera.withGesture {
        moveBy(10.0, 0.0)
        state.invalidatePresentation(target)
        assertFailsWith<IllegalStateException> { moveBy(20.0, 0.0) }
      }
    }
    runCurrent()
    target.drain()
    runCurrent()
    assertTrue(work.isCompleted)
    assertTrue(target.moves.isEmpty())
  }

  @Test
  fun same_state_nesting_is_rejected_before_it_can_cancel_the_parent() =
    cameraTest { state, target ->
      val work = launch {
        state.gestureCamera.withGesture {
          assertFailsWith<IllegalStateException> { state.gestureCamera.withGesture {} }
          moveBy(10.0, 0.0)
        }
      }
      runCurrent()
      target.drain()
      runCurrent()
      assertTrue(work.isCompleted)
      assertFalse(work.isCancelled)
      assertEquals(listOf(10.0), target.moves)
    }

  @Test
  fun different_states_can_nest_but_a_to_b_to_a_cannot() = cameraTest { state, target ->
    val other = state.runtime.createMapState(BaseStyle.Empty)
    val otherTarget = present(other)
    val work = launch {
      state.gestureCamera.withGesture {
        moveBy(10.0, 0.0)
        other.gestureCamera.withGesture {
          assertFailsWith<IllegalStateException> { state.gestureCamera.withGesture {} }
          moveBy(20.0, 0.0)
        }
        moveBy(30.0, 0.0)
      }
    }
    runCurrent()
    otherTarget.drain()
    runCurrent()
    target.drain()
    runCurrent()
    assertTrue(work.isCompleted)
    assertEquals(listOf(10.0, 30.0), target.moves)
    assertEquals(listOf(20.0), otherTarget.moves)
    other.close()
    other.awaitClosed()
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

  private fun cameraTest(body: suspend TestScope.(MapState, QueuedGestureTarget) -> Unit) =
    runTest {
      val runtime = mapRuntimeForTest(physicalScope = backgroundScope)
      val state = runtime.createMapState(BaseStyle.Empty)
      val target = present(state)
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

  private fun present(state: MapState): QueuedGestureTarget {
    val target = QueuedGestureTarget(state)
    state.publishPresentation(state.reservePresentation(), target)
    state.synchronizeCamera(target)
    return target
  }
}

/** A paused owner queue. Native/JS tests separately verify the actual command paths. */
private class QueuedGestureTarget(private val state: MapState) :
  PresentationTestAdapter(), GestureTarget {
  override fun positionFromScreenLocation(offset: DpOffset): Position? = null

  val moves = mutableListOf<Double>()
  private val pending = ArrayDeque<() -> Unit>()

  init {
    val a = Position(-1.0, -1.0)
    val b = Position(1.0, 1.0)
    currentViewport =
      Viewport(
        size = DpSize(100.dp, 100.dp),
        visibleBoundingBox = BoundingBox(a, b),
        visibleRegion = VisibleRegion(a, b, a, b),
        metersPerDpAtTarget = 1.0,
      )
  }

  override fun getCameraPosition(): CameraPosition = lastCameraPosition

  override fun cancelTransitions() = Unit

  override fun observeInput(): Long = state.gestureAuthority.observeInput()

  override val inputGeneration: Long
    get() = state.gestureAuthority.generation

  override fun onGestureStartedIfCurrent(generation: Long): GestureToken? =
    state.gestureAuthority.acquireIfCurrent(this, generation)

  override fun onGestureStarted(): GestureToken = state.gestureAuthority.acquire(this)

  override fun onGestureEnded(token: GestureToken) {
    token.finish(false) { pending.add { token.complete() } }
  }

  override fun cancelGesture(token: GestureToken) {
    token.finish(true) { pending.add { token.complete() } }
  }

  override suspend fun awaitGestureEnded(token: GestureToken) {
    token.completion.await()
  }

  override fun moveBy(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: GestureToken?,
  ) {
    checkNotNull(gestureToken).enqueue {
      pending.add { if (gestureToken.canExecute) moves += deltaX }
    }
  }

  override fun scaleBy(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: GestureToken?,
  ) = Unit

  override fun rotateAndPitchBy(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    anchor: DpOffset?,
    gestureToken: GestureToken?,
  ) = Unit

  override suspend fun moveByAwaitingTransition(
    deltaX: Double,
    deltaY: Double,
    duration: Duration,
    gestureToken: GestureToken,
  ) = Unit

  override suspend fun scaleByAwaitingTransition(
    scale: Double,
    anchor: DpOffset?,
    duration: Duration,
    gestureToken: GestureToken,
  ) = Unit

  override suspend fun rotateAndPitchByAwaitingTransition(
    bearingDelta: Double,
    pitchDelta: Double,
    duration: Duration,
    gestureToken: GestureToken,
    anchor: DpOffset?,
  ) = Unit

  fun drain() {
    while (pending.isNotEmpty()) pending.removeFirst().invoke()
  }
}
