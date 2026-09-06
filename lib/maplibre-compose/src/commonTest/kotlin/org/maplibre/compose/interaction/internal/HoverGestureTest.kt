package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.interaction.HoverEvent
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalCoroutinesApi::class)
class MapHoverGestureTest {
  @Test
  fun hover_reports_pointer_positions_and_balances_exit() = hoverTest { fixture ->
    fixture.project = { Position(it.x.value.toDouble(), 0.0) }
    fixture.hover.move(sample(10))
    fixture.hover.move(sample(20))
    val events = mutableListOf<HoverEvent>()
    fixture.onHover = { events += it }
    applyChanges()
    fixture.hover.move(sample(30))
    fixture.hover.exit()
    fixture.hover.exit()
    assertEquals(listOf("enter", "move", "exit"), events.map(::kind))
    assertEquals(listOf(20.dp, 30.dp, 30.dp), events.map { it.screenOffset.x })
    assertEquals(
      listOf(Position(20.0, 0.0), Position(30.0, 0.0), Position(30.0, 0.0)),
      events.map { it.position },
    )
    assertEquals(1, events.map { it.gestureId }.distinct().size)
  }

  @Test
  fun callback_updates_do_not_emit_movement_and_removal_exits_through_the_latest_body() =
    hoverTest { fixture ->
      val old = mutableListOf<HoverEvent>()
      val replacement = mutableListOf<HoverEvent>()
      fixture.onHover = { old += it }
      fixture.hover.move(sample(10))
      applyChanges()
      fixture.onHover = { replacement += it }
      applyChanges()
      assertEquals(listOf("enter"), old.map(::kind))
      assertTrue(replacement.isEmpty())
      val latest = mutableListOf<HoverEvent>()
      fixture.onHover = { latest += it }
      fixture.hover.exit()
      assertEquals(listOf("exit"), latest.map(::kind))
      assertTrue(replacement.isEmpty())
      fixture.hover.move(sample(20))
      fixture.onHover = null
      applyChanges()
      assertEquals(listOf("exit", "enter", "exit"), latest.map(::kind))
    }

  @Test
  fun removal_and_readdition_between_samples_starts_a_new_hover() = hoverTest { fixture ->
    val events = mutableListOf<HoverEvent>()
    val handler: (HoverEvent) -> Unit = { events += it }
    fixture.onHover = handler
    fixture.hover.move(sample(10))
    applyChanges()
    fixture.onHover = null
    fixture.onHover = handler
    applyChanges()
    assertEquals(listOf("enter", "exit", "enter"), events.map(::kind))
    assertEquals(2, events.map { it.gestureId }.distinct().size)
  }

  @Test
  fun disabled_hover_exits_once_and_ignores_further_input() = hoverTest { fixture ->
    val events = mutableListOf<HoverEvent>()
    fixture.onHover = { events += it }
    fixture.hover.move(sample(10))
    fixture.options =
      MapInteractions(from = fixture.options) { bindings { hover { enabled = false } } }
    applyChanges()
    fixture.hover.move(sample(20))
    fixture.hover.exit()
    assertEquals(listOf("enter", "exit"), events.map(::kind))
  }

  @Test
  fun exiting_from_a_callback_does_not_restore_hover_or_exit_twice() = hoverTest { fixture ->
    val events = mutableListOf<HoverEvent>()
    fixture.onHover = {
      events += it
      if (it is HoverEvent.Enter) fixture.hover.exit()
    }
    fixture.hover.move(sample(10))
    applyChanges()
    fixture.hover.exit()
    assertEquals(listOf("enter", "exit"), events.map(::kind))
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

  private class Fixture(scope: CoroutineScope) {
    var project: (DpOffset) -> Position? = { null }
    private var configuredOptions by mutableStateOf(MapInteractions.Standard)
    val subscriptions = InteractionSubscriptions(MapInteractions.Standard)
    var options: MapInteractions
      get() = configuredOptions
      set(value) {
        subscriptions.update(value)
        configuredOptions = value
      }

    var onHover: ((HoverEvent) -> Unit)?
      get() = options.callbacks.hover
      set(value) {
        options = MapInteractions(from = options) { callbacks { hover { onEvent(value) } } }
      }

    val hover =
      HoverGesture(scope, { project(it) }, { options }, GestureIds(), Density(1f), subscriptions)
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
