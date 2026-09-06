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
          val options = MapInteractions {
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
          val target =
            object : MapInteractionTarget {
              override fun capture(family: TapFamily) =
                MapClickPath({ true }) {
                  order += "map"
                  error("map failed")
                }
            }
          val dispatcher =
            MapTapDispatcher(scope, target, InteractionSubscriptions(options)) { options }
          dispatcher.dispatch(checkNotNull(dispatcher.capture(TapFamily.DoubleTap)), sample(1)) {
            order += "camera"
          }
          dispatcher.dispatch(checkNotNull(dispatcher.capture(TapFamily.DoubleTap)), sample(2)) {
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
    val options = MapInteractions {
      callbacks {
        click {
          onEvent {
            order += "binding ${it.gestureId}"
            ClickResult.Pass
          }
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
    val dispatcher =
      MapTapDispatcher(backgroundScope, target, InteractionSubscriptions(options)) { options }
    dispatcher.dispatch(checkNotNull(dispatcher.capture(TapFamily.Tap)), sample(1)) {
      order += "camera 1"
    }
    dispatcher.dispatch(checkNotNull(dispatcher.capture(TapFamily.Tap)), sample(2)) {
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
    val target =
      object : MapInteractionTarget {
        override fun capture(family: TapFamily) =
          MapClickPath({ true }) {
            delivery++
            ClickResult.Pass
          }
      }
    val options = MapInteractions { callbacks { doubleClick { onEvent { ClickResult.Consume } } } }
    val dispatcher =
      MapTapDispatcher(backgroundScope, target, InteractionSubscriptions(options)) { options }
    dispatcher.dispatch(checkNotNull(dispatcher.capture(TapFamily.DoubleTap)), sample(1)) {
      cameras++
    }
    testScheduler.runCurrent()
    assertEquals(0, delivery)
    assertEquals(0, cameras)
  }

  @Test
  fun a_structural_change_during_a_query_cancels_camera_fallthrough() = runTest {
    var options = MapInteractions.Standard
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
    val dispatcher =
      MapTapDispatcher(backgroundScope, target, InteractionSubscriptions(options)) { options }
    dispatcher.dispatch(checkNotNull(dispatcher.capture(TapFamily.DoubleTap)), sample(1)) {
      cameras++
    }
    testScheduler.runCurrent()
    options = MapInteractions.None
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
    val dispatcher =
      MapTapDispatcher(
        backgroundScope,
        target,
        InteractionSubscriptions(MapInteractions.Standard),
      ) {
        MapInteractions.Standard
      }
    dispatcher.dispatch(checkNotNull(dispatcher.capture(TapFamily.DoubleTap)), sample(1)) {
      cameras += 1
    }
    dispatcher.dispatch(checkNotNull(dispatcher.capture(TapFamily.DoubleTap)), sample(2)) {
      cameras += 2
    }
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
    val options = MapInteractions {
      callbacks {
        click {
          onEvent {
            valid = false
            ClickResult.Pass
          }
        }
      }
    }
    val dispatcher =
      MapTapDispatcher(backgroundScope, target, InteractionSubscriptions(options)) { options }
    dispatcher.dispatch(checkNotNull(dispatcher.capture(TapFamily.Tap)), sample(1)) {
      error("invalid camera fallthrough")
    }
    testScheduler.runCurrent()
    assertEquals(0, delivery)
  }

  @Test
  fun press_admission_keeps_slots_but_uses_current_bodies_and_allows_self_removal() = runTest {
    val order = mutableListOf<String>()
    var options = MapInteractions.Standard
    val target =
      object : MapInteractionTarget {
        override fun capture(family: TapFamily) =
          MapClickPath({ true }) {
            order += "layers"
            ClickResult.Pass
          }
      }
    val subscriptions = InteractionSubscriptions(options)
    val dispatcher = MapTapDispatcher(backgroundScope, target, subscriptions) { options }
    val beforeSubscription = checkNotNull(dispatcher.capture(TapFamily.Tap))
    options = MapInteractions {
      callbacks { click { onEvent { error("replaced body") } } }
    }
    subscriptions.update(options)
    val admitted = checkNotNull(dispatcher.capture(TapFamily.Tap))
    options = MapInteractions {
      callbacks {
        click {
          onEvent {
            order += "current map"
            options = MapInteractions { callbacks { click { onEvent(null) } } }
            subscriptions.update(options)
            ClickResult.Pass
          }
        }
      }
    }
    subscriptions.update(options)
    dispatcher.dispatch(beforeSubscription, sample(1)) { order += "first camera" }
    dispatcher.dispatch(admitted, sample(2)) { order += "second camera" }
    testScheduler.runCurrent()
    assertEquals(listOf("layers", "first camera", "current map", "layers", "second camera"), order)
  }

  @Test
  fun removing_and_readding_a_map_callback_does_not_rejoin_an_admitted_click() = runTest {
    var calls = 0
    var options = MapInteractions {
      callbacks {
        click {
          onEvent {
            calls++
            ClickResult.Pass
          }
        }
      }
    }
    val subscriptions = InteractionSubscriptions(options)
    val target =
      object : MapInteractionTarget {
        override fun capture(family: TapFamily) = MapClickPath({ true }) { ClickResult.Pass }
      }
    val dispatcher = MapTapDispatcher(backgroundScope, target, subscriptions) { options }
    val admitted = checkNotNull(dispatcher.capture(TapFamily.Tap))
    subscriptions.update(MapInteractions.Standard)
    options = MapInteractions {
      callbacks {
        click {
          onEvent {
            calls++
            ClickResult.Pass
          }
        }
      }
    }
    subscriptions.update(options)
    var camera = 0
    dispatcher.dispatch(admitted, sample(1)) { camera++ }
    testScheduler.runCurrent()
    assertEquals(0, calls)
    assertEquals(1, camera)
    dispatcher.dispatch(checkNotNull(dispatcher.capture(TapFamily.Tap)), sample(2)) { camera++ }
    testScheduler.runCurrent()
    assertEquals(1, calls)
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
