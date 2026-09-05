package org.maplibre.compose.map

import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.util.ClickResult

@OptIn(ExperimentalCoroutinesApi::class)
class MapTapDispatcherTest {
  @Test
  fun callback_failure_reaches_coroutine_error_handling_without_falling_through_or_replaying() =
    runTest {
      for (failInBinding in listOf(true, false)) {
        val errors = mutableListOf<Throwable>()
        val order = mutableListOf<String>()
        val work = SupervisorJob(backgroundScope.coroutineContext[Job])
        val scope =
          CoroutineScope(
            backgroundScope.coroutineContext +
              work +
              CoroutineExceptionHandler { _, error -> errors += error }
          )
        try {
          val options = MapGestures {
            doubleTap {
              onEvent {
                order += "binding"
                if (failInBinding) error("binding failed")
                ClickResult.Pass
              }
            }
          }
          val target =
            object : MapInteractionTarget {
              override fun capture(family: TapFamily) =
                MapClickPath({ true }) {
                  order += "map"
                  error("map failed")
                }
            }
          val dispatcher = MapTapDispatcher(scope, target) { options }
          dispatcher.dispatch(TapFamily.DoubleTap, sample(1)) { order += "camera" }
          dispatcher.dispatch(TapFamily.DoubleTap, sample(2)) { order += "next camera" }
          testScheduler.runCurrent()
          assertEquals(1, errors.size)
          assertEquals(
            if (failInBinding) "binding failed" else "map failed",
            errors.single().message,
          )
          assertEquals(if (failInBinding) listOf("binding") else listOf("binding", "map"), order)
        } finally {
          work.cancel()
        }
      }
    }

  @Test
  fun recognition_order_is_preserved_across_a_suspended_query() = runTest {
    val order = mutableListOf<String>()
    val query = CompletableDeferred<Unit>()
    val options = MapGestures {
      tap {
        onEvent {
          order += "binding ${it.gestureId}"
          ClickResult.Pass
        }
      }
    }
    val target =
      object : MapInteractionTarget {
        override fun capture(family: TapFamily) =
          MapClickPath({ true }) {
            order += "map ${it.gestureId}"
            if (it.gestureId == 1L) query.await()
            order += "layer ${it.gestureId}"
            ClickResult.Pass
          }
      }
    val dispatcher = MapTapDispatcher(backgroundScope, target) { options }
    dispatcher.dispatch(TapFamily.Tap, sample(1)) { order += "camera 1" }
    dispatcher.dispatch(TapFamily.Tap, sample(2)) { order += "camera 2" }
    testScheduler.runCurrent()
    assertEquals(listOf("binding 1", "map 1"), order)
    query.complete(Unit)
    testScheduler.runCurrent()
    assertEquals(
      listOf(
        "binding 1",
        "map 1",
        "layer 1",
        "camera 1",
        "binding 2",
        "map 2",
        "layer 2",
        "camera 2",
      ),
      order,
    )
  }

  @Test
  fun binding_consumption_stops_the_entire_application_and_camera_path() = runTest {
    var delivery = 0
    var cameras = 0
    val target =
      object : MapInteractionTarget {
        override fun capture(family: TapFamily) =
          MapClickPath({ true }) {
            delivery++
            ClickResult.Pass
          }
      }
    val dispatcher =
      MapTapDispatcher(backgroundScope, target) {
        MapGestures { doubleTap { onEvent { ClickResult.Consume } } }
      }
    dispatcher.dispatch(TapFamily.DoubleTap, sample(1)) { cameras++ }
    testScheduler.runCurrent()
    assertEquals(0, delivery)
    assertEquals(0, cameras)
  }

  @Test
  fun a_structural_change_during_a_query_cancels_camera_fallthrough() = runTest {
    var options = MapGestures.Standard
    val query = CompletableDeferred<Unit>()
    var cameras = 0
    val target =
      object : MapInteractionTarget {
        override fun capture(family: TapFamily) =
          MapClickPath({ true }) {
            query.await()
            ClickResult.Pass
          }
      }
    val dispatcher = MapTapDispatcher(backgroundScope, target) { options }
    dispatcher.dispatch(TapFamily.DoubleTap, sample(1)) { cameras++ }
    testScheduler.runCurrent()
    options = MapGestures.None
    query.complete(Unit)
    testScheduler.runCurrent()
    assertEquals(0, cameras)
  }

  @Test
  fun cancellation_of_one_lease_bound_query_does_not_become_pass_or_kill_later_clicks() = runTest {
    val cameras = mutableListOf<Long>()
    val target =
      object : MapInteractionTarget {
        override fun capture(family: TapFamily): MapClickPath {
          var valid = true
          return MapClickPath({ valid }) {
            if (it.gestureId == 1L) {
              valid = false
              throw CancellationException("attachment changed")
            }
            ClickResult.Pass
          }
        }
      }
    val dispatcher = MapTapDispatcher(backgroundScope, target) { MapGestures.Standard }
    dispatcher.dispatch(TapFamily.DoubleTap, sample(1)) { cameras += 1 }
    dispatcher.dispatch(TapFamily.DoubleTap, sample(2)) { cameras += 2 }
    testScheduler.runCurrent()
    assertEquals(listOf(2L), cameras)
  }

  @Test
  fun invalidating_the_path_in_a_binding_observer_stops_the_next_stage() = runTest {
    var valid = true
    var delivery = 0
    val target =
      object : MapInteractionTarget {
        override fun capture(family: TapFamily) =
          MapClickPath({ valid }) {
            delivery++
            ClickResult.Pass
          }
      }
    val options = MapGestures {
      tap {
        onEvent {
          valid = false
          ClickResult.Pass
        }
      }
    }
    val dispatcher = MapTapDispatcher(backgroundScope, target) { options }
    dispatcher.dispatch(TapFamily.Tap, sample(1)) { error("invalid camera fallthrough") }
    testScheduler.runCurrent()
    assertEquals(0, delivery)
  }

  private fun sample(id: Long) =
    GesturePointerSample(
      id,
      10,
      DpOffset.Zero,
      null,
      setOf(PointerType.Touch),
      emptySet(),
      emptySet(),
    )
}
