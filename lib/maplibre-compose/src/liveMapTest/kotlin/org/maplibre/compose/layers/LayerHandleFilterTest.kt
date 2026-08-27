package org.maplibre.compose.layers

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.value.EquatableValue
import org.maplibre.compose.map.FakeMapAdapter
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.RecordingHostDispatcher
import org.maplibre.compose.style.OpRecordingStyleBinding
import org.maplibre.compose.util.toStyleJson

@OptIn(ExperimentalCoroutinesApi::class)
class LayerHandleFilterTest {

  private fun TestScope.mapState() =
    MapState(
      cameraPosition = CameraPosition(),
      density = Density(1f),
      layoutDirection = LayoutDirection.Ltr,
      logger = null,
      hostDispatcher = RecordingHostDispatcher(StandardTestDispatcher(testScheduler)),
    )

  private fun TestScope.attach(state: MapState, binding: OpRecordingStyleBinding) {
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, binding)
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun a_filter_write_round_trips_through_the_handle() = runTest {
    val state = mapState()
    state.setStyleContent {}
    val binding = OpRecordingStyleBinding(baseLayers = listOf(BackgroundLayerDescriptor("bg-base")))
    attach(state, binding)

    val handle = assertNotNull(state.layers["bg-base"])
    assertEquals(nil(), handle.filter, "an unset filter reads as nil")

    val written = feature.get("class").cast<EquatableValue>() eq const("park")
    handle.filter = written
    val readBack = assertNotNull(state.layers["bg-base"]).filter

    val expected = written.compile(ExpressionContext.None).toStyleJson()
    assertEquals(expected, readBack.compile(ExpressionContext.None).toStyleJson())

    handle.filter = nil()
    assertNull(
      assertNotNull(state.layers["bg-base"]).property("filter"),
      "assigning nil clears the filter",
    )

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun a_filter_write_on_a_composition_owned_layer_throws() = runTest {
    val state = mapState()
    state.setStyleContent { BackgroundLayer(id = "comp-layer") }
    attach(state, OpRecordingStyleBinding())

    val handle = assertNotNull(state.layers["comp-layer"])
    assertFailsWith<IllegalStateException> { handle.filter = const(true) }

    state.close()
    testScheduler.advanceUntilIdle()
  }
}
