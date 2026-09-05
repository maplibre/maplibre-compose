package org.maplibre.compose.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraPosition

@OptIn(ExperimentalCoroutinesApi::class)
class MapHoverGestureTest {
  @Test
  fun map_observation_needs_no_query_and_exit_does_not_need_projection() = hoverTest { fixture ->
    val events = mutableListOf<HoverEvent>()
    fixture.onHover = { events += it }
    fixture.hover.move(sample(10))
    fixture.hover.move(sample(20))
    fixture.hover.exit()
    fixture.hover.exit()
    assertEquals(listOf("enter", "move", "exit"), events.map(::kind))
    assertTrue(events.all { it.position == null })
    assertEquals(1, events.map { it.gestureId }.distinct().size)
    assertTrue(fixture.source.queries.isEmpty())
  }

  @Test
  fun only_the_latest_position_before_a_frame_is_queried() = hoverTest { fixture ->
    val events = fixture.layer()
    fixture.hover.move(sample(10))
    fixture.hover.move(sample(20))
    fixture.hover.move(sample(30))
    frame(fixture)
    assertEquals(listOf(30f), fixture.source.queries)
    assertEquals(30.dp, events.single().screenOffset.x)
    assertTrue(events.single() is HoverEvent.Enter)
  }

  @Test
  fun a_valid_completed_pass_is_published_even_while_a_newer_move_waits() = hoverTest { fixture ->
    val events = fixture.layer()
    val query = CompletableDeferred<Unit>()
    fixture.source.beforeQuery = { if (it.x == 10.dp) query.await() }
    fixture.hover.move(sample(10))
    frame(fixture)
    fixture.hover.move(sample(20))
    fixture.hover.move(sample(30))
    query.complete(Unit)
    runCurrent()
    assertEquals(listOf(10f), fixture.source.queries)
    assertEquals(10.dp, events.single().screenOffset.x)
    frame(fixture)
    assertEquals(listOf(10f, 30f), fixture.source.queries)
    assertEquals(listOf("enter", "move"), events.map(::kind))
    assertEquals(30.dp, events.last().screenOffset.x)
    assertEquals(1, fixture.source.maximumQueries)
  }

  @Test
  fun removing_and_replacing_a_registration_exits_the_old_callback_once() = hoverTest { fixture ->
    val old = fixture.layer()
    fixture.hover.move(sample(10))
    frame(fixture)
    val next = fixture.layer()
    applyChanges()
    assertEquals(listOf("enter", "exit"), old.map(::kind))
    frame(fixture)
    assertEquals(listOf("enter"), next.map(::kind))
    fixture.hover.exit()
    assertEquals(listOf("enter", "exit"), next.map(::kind))
    assertEquals(2, old.size)
  }

  @Test
  fun resubscribing_a_layer_cannot_publish_its_cancelled_pre_subscription_query() =
    hoverTest { fixture ->
      fixture.onHover = {}
      val events = fixture.layer()
      val registration = fixture.source.layers
      val query = CompletableDeferred<Unit>()
      fixture.source.beforeQuery = {
        withContext(NonCancellable) { query.await() }
      }
      fixture.hover.move(sample(10))
      frame(fixture)
      fixture.source.layers = emptyList()
      applyChanges()
      fixture.source.layers = registration
      applyChanges()
      query.complete(Unit)
      runCurrent()
      assertTrue(events.isEmpty())
      frame(fixture)
      assertTrue(events.single() is HoverEvent.Enter)
      assertEquals(1, fixture.source.maximumQueries)
    }

  @Test
  fun removal_uses_an_updated_callback_on_the_surviving_registration() = hoverTest { fixture ->
    val old = fixture.layer()
    fixture.hover.move(sample(10))
    frame(fixture)
    val updated = mutableListOf<HoverEvent>()
    fixture.source.layers =
      fixture.source.layers.map { it.copy(handler = { event -> updated += event }) }
    applyChanges()
    fixture.source.layers = emptyList()
    applyChanges()
    assertEquals(listOf("enter"), old.map(::kind))
    assertEquals(listOf("exit"), updated.map(::kind))
  }

  @Test
  fun presentation_changes_resample_a_stationary_pointer() = hoverTest { fixture ->
    val events = fixture.layer()
    fixture.hover.move(sample(10))
    frame(fixture)
    fixture.source.hit = false
    fixture.source.revision++
    applyChanges()
    frame(fixture)
    assertEquals(listOf(10f, 10f), fixture.source.queries)
    assertEquals(listOf("enter", "exit"), events.map(::kind))
  }

  @Test
  fun an_exited_pass_cannot_publish_or_overlap_the_next_query_even_if_cancellation_is_delayed() =
    hoverTest { fixture ->
      val events = fixture.layer()
      val query = CompletableDeferred<Unit>()
      fixture.source.beforeQuery = {
        if (it.x == 10.dp) withContext(NonCancellable) { query.await() }
      }
      fixture.hover.move(sample(10))
      frame(fixture)
      fixture.hover.exit()
      fixture.hover.move(sample(20))
      runCurrent()
      assertEquals(listOf(10f), fixture.source.queries)
      query.complete(Unit)
      runCurrent()
      assertTrue(events.isEmpty())
      frame(fixture)
      assertEquals(listOf(10f, 20f), fixture.source.queries)
      assertEquals(1, fixture.source.maximumQueries)
      assertEquals(20.dp, events.single().screenOffset.x)
    }

  @Test
  fun disabling_hover_clears_membership_and_ignores_further_samples() = hoverTest { fixture ->
    val events = fixture.layer()
    fixture.hover.move(sample(10))
    frame(fixture)
    fixture.options = MapGestures { hover { enabled = false } }
    applyChanges()
    fixture.hover.move(sample(20))
    runCurrent()
    assertEquals(listOf("enter", "exit"), events.map(::kind))
    assertEquals(listOf(10f), fixture.source.queries)
  }

  @Test
  fun exiting_from_a_map_callback_cannot_enter_layers() = hoverTest { fixture ->
    val events = mutableListOf<HoverEvent>()
    fixture.options = MapGestures {
      hover {
        onEvent {
          events += it
          if (it is HoverEvent.Enter) fixture.hover.exit()
        }
      }
    }
    val layerEvents = fixture.layer()
    fixture.hover.move(sample(10))
    applyChanges()
    assertEquals(listOf("enter", "exit"), events.map(::kind))
    assertTrue(layerEvents.isEmpty())
    assertTrue(fixture.source.queries.isEmpty())
  }

  @Test
  fun one_throwing_exit_still_balances_the_other_observers() = hoverTest { fixture ->
    var mapExits = 0
    val failure = IllegalStateException("exit")
    fixture.options = MapGestures { hover { onEvent { if (it is HoverEvent.Exit) throw failure } } }
    fixture.source.layers =
      listOf(HoverLayer("layer", Any()) { if (it is HoverEvent.Exit) mapExits++ })
    fixture.hover.move(sample(10))
    frame(fixture)
    assertEquals(failure, assertFailsWith<IllegalStateException> { fixture.hover.exit() })
    fixture.hover.exit()
    assertEquals(1, mapExits)
  }

  private fun hoverTest(body: suspend TestScope.(Fixture) -> Unit) = runTest {
    val fixture = Fixture(backgroundScope)
    runCurrent()
    try {
      body(fixture)
    } finally {
      fixture.hover.exit()
    }
  }

  private fun TestScope.applyChanges() {
    Snapshot.sendApplyNotifications()
    runCurrent()
  }

  private fun TestScope.frame(fixture: Fixture) {
    applyChanges()
    assertTrue(fixture.frames.trySend(Unit).isSuccess, "no pass was waiting for a frame")
    runCurrent()
  }

  private class Fixture(scope: CoroutineScope) {
    val source = Source()
    val frames = Channel<Unit>(Channel.RENDEZVOUS)
    var options by mutableStateOf(MapGestures.Standard)
    var onHover: ((HoverEvent) -> Unit)?
      get() = options.binding("hover").handlers.hover
      set(value) {
        options = MapGestures(from = options) { hover { onEvent(value) } }
      }

    val hover =
      MapHoverGesture(
        scope,
        NoCamera,
        source,
        { options },
        GestureIds(),
        Density(1f),
        { frames.receive() },
      )

    fun layer(): MutableList<HoverEvent> {
      val events = mutableListOf<HoverEvent>()
      source.layers = listOf(HoverLayer("layer", Any()) { events += it })
      return events
    }
  }

  private class Source : MapInteractionTarget {
    var revision by mutableIntStateOf(0)
    var layers by mutableStateOf(emptyList<HoverLayer>())
    var hit = true
    var beforeQuery: suspend (DpOffset) -> Unit = {}
    val queries = mutableListOf<Float>()
    var activeQueries = 0
    var maximumQueries = 0
    override val hoverRevision: Any
      get() = listOf(revision, layers)

    override fun capture(family: TapFamily): MapClickPath? = error("hover does not dispatch clicks")

    override fun captureHover() =
      HoverScene(Unit, Unit, layers, { true }) { _, offset ->
        queries += offset.x.value
        activeQueries++
        maximumQueries = maxOf(maximumQueries, activeQueries)
        try {
          beforeQuery(offset)
          hit
        } finally {
          activeQueries--
        }
      }
  }

  private object NoCamera : GestureTarget {
    override fun cancelTransitions(): Unit = error("hover has no camera authority")

    override fun getCameraPosition(): CameraPosition = error("hover has no camera authority")

    override fun onGestureStarted(): GestureToken = error("hover has no camera authority")

    override fun onGestureEnded(token: GestureToken): Unit = error("hover has no camera authority")

    override fun moveBy(
      deltaX: Double,
      deltaY: Double,
      duration: Duration,
      gestureToken: GestureToken?,
    ): Unit = error("hover has no camera authority")

    override fun scaleBy(
      scale: Double,
      anchor: DpOffset?,
      duration: Duration,
      gestureToken: GestureToken?,
    ): Unit = error("hover has no camera authority")

    override fun rotateAndPitchBy(
      bearingDelta: Double,
      pitchDelta: Double,
      duration: Duration,
      anchor: DpOffset?,
      gestureToken: GestureToken?,
    ): Unit = error("hover has no camera authority")

    override suspend fun moveByAwaitingTransition(
      deltaX: Double,
      deltaY: Double,
      duration: Duration,
      gestureToken: GestureToken,
    ): Unit = error("hover has no camera authority")

    override suspend fun scaleByAwaitingTransition(
      scale: Double,
      anchor: DpOffset?,
      duration: Duration,
      gestureToken: GestureToken,
    ): Unit = error("hover has no camera authority")

    override suspend fun rotateAndPitchByAwaitingTransition(
      bearingDelta: Double,
      pitchDelta: Double,
      duration: Duration,
      gestureToken: GestureToken,
      anchor: DpOffset?,
    ): Unit = error("hover has no camera authority")
  }

  companion object {
    private fun sample(x: Int) =
      GesturePointerSample(
        0,
        x.toLong(),
        DpOffset(x.dp, 0.dp),
        null,
        setOf(PointerType.Mouse),
        emptySet(),
        emptySet(),
      )

    private fun kind(event: HoverEvent): String =
      when (event) {
        is HoverEvent.Enter -> "enter"
        is HoverEvent.Move -> "move"
        is HoverEvent.Exit -> "exit"
      }
  }
}
