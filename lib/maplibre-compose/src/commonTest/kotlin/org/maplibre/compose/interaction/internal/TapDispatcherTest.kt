package org.maplibre.compose.interaction.internal

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
import org.maplibre.compose.interaction.ClickResult

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
          val options = InputConfiguration {
            callbacks {
              doubleClick {
                onEvent {
                  order += "binding"
                  if (failInBinding) error("binding failed")
                  ClickResult.Pass
                }
              }
            }
          }
          val target = { _: TapFamily ->
            ClickPath({ true }) {
              order += "map"
              error("map failed")
            }
          }
          val dispatcher = TapDispatcher(scope, target, { false }) { options }
          dispatcher.dispatch(TapFamily.DoubleTap, sample(1)) {
            order += "camera"
          }
          dispatcher.dispatch(TapFamily.DoubleTap, sample(2)) {
            order += "next camera"
          }
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
    val options = InputConfiguration {
      callbacks {
        click {
          onEvent {
            order += "binding ${it.uptimeMillis}"
            ClickResult.Pass
          }
        }
      }
    }
    val target = { _: TapFamily ->
      ClickPath({ true }) {
        order += "map ${it.uptimeMillis}"
        if (it.uptimeMillis == 1L) query.await()
        order += "layer ${it.uptimeMillis}"
        ClickResult.Pass
      }
    }
    val dispatcher = TapDispatcher(backgroundScope, target, { false }) { options }
    dispatcher.dispatch(TapFamily.Tap, sample(1)) {
      order += "camera 1"
    }
    dispatcher.dispatch(TapFamily.Tap, sample(2)) {
      order += "camera 2"
    }
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
    val target = { _: TapFamily ->
      ClickPath({ true }) {
        delivery++
        ClickResult.Pass
      }
    }
    val options = InputConfiguration {
      callbacks { doubleClick { onEvent { ClickResult.Consume } } }
    }
    val dispatcher = TapDispatcher(backgroundScope, target, { false }) { options }
    dispatcher.dispatch(TapFamily.DoubleTap, sample(1)) {
      cameras++
    }
    testScheduler.runCurrent()
    assertEquals(0, delivery)
    assertEquals(0, cameras)
  }

  @Test
  fun cancellation_of_one_lease_bound_query_does_not_become_pass_or_kill_later_clicks() = runTest {
    val cameras = mutableListOf<Long>()
    val target = { _: TapFamily ->
      var valid = true
      ClickPath({ valid }) {
        if (it.uptimeMillis == 1L) {
          valid = false
          throw CancellationException("attachment changed")
        }
        ClickResult.Pass
      }
    }
    val dispatcher =
      TapDispatcher(
        backgroundScope,
        target,
        { false },
      ) {
        InputConfiguration.Standard
      }
    dispatcher.dispatch(TapFamily.DoubleTap, sample(1)) {
      cameras += 1
    }
    dispatcher.dispatch(TapFamily.DoubleTap, sample(2)) {
      cameras += 2
    }
    testScheduler.runCurrent()
    assertEquals(listOf(2L), cameras)
  }

  @Test
  fun invalidating_the_path_in_a_binding_observer_stops_the_next_stage() = runTest {
    var valid = true
    var delivery = 0
    val target = { _: TapFamily ->
      ClickPath({ valid }) {
        delivery++
        ClickResult.Pass
      }
    }
    val options = InputConfiguration {
      callbacks {
        click {
          onEvent {
            valid = false
            ClickResult.Pass
          }
        }
      }
    }
    val dispatcher = TapDispatcher(backgroundScope, target, { false }) { options }
    dispatcher.dispatch(TapFamily.Tap, sample(1)) {
      error("invalid camera fallthrough")
    }
    testScheduler.runCurrent()
    assertEquals(0, delivery)
  }

  @Test
  fun queued_click_uses_the_current_callback() = runTest {
    val order = mutableListOf<String>()
    var options = InputConfiguration {
      callbacks { click { onEvent { error("replaced callback") } } }
    }
    val dispatcher =
      TapDispatcher(
        backgroundScope,
        {
          ClickPath({ true }) {
            order += "layer"
            ClickResult.Pass
          }
        },
        { true },
      ) {
        options
      }
    dispatcher.dispatch(TapFamily.Tap, sample(1)) { order += "camera" }
    options = InputConfiguration {
      callbacks {
        click {
          onEvent {
            order += "current callback"
            ClickResult.Pass
          }
        }
      }
    }
    testScheduler.runCurrent()
    assertEquals(listOf("current callback", "layer", "camera"), order)
  }

  private fun sample(id: Long) =
    GesturePointerSample(
      id,
      DpOffset.Zero,
      null,
      setOf(PointerType.Touch),
      emptySet(),
      emptySet(),
    )
}
