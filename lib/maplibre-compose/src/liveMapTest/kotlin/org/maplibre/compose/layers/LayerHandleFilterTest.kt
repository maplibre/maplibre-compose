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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.ast.ExpressionContext
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.nil
import org.maplibre.compose.expressions.value.EquatableValue
import org.maplibre.compose.map.FakeMapAdapter
import org.maplibre.compose.map.MapState
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
      hostDispatcher = StandardTestDispatcher(testScheduler),
    )

  private fun TestScope.attach(state: MapState, binding: OpRecordingStyleBinding) {
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, binding)
    testScheduler.advanceUntilIdle()
  }

  private class FilterRecordingBinding(baseLayers: List<Layer>) :
    OpRecordingStyleBinding(baseLayers = baseLayers) {
    val filters = mutableListOf<Pair<String, JsonElement>>()

    override fun setLayerFilter(layerId: String, filter: JsonElement): Boolean {
      filters.add(layerId to filter)
      return super.setLayerFilter(layerId, filter)
    }
  }

  @Test
  fun a_filter_write_reaches_the_binding() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    val descriptor = BackgroundLayerDescriptor("bg-base")
    val binding = FilterRecordingBinding(baseLayers = listOf(descriptor))
    attach(state, binding)
    descriptor.bindExisting(binding)

    val handle = assertNotNull(state.layers["bg-base"])
    assertNull(handle.property("filter"), "an unset filter reads as null")

    val written = feature.get("class").cast<EquatableValue>() eq const("park")
    handle.setFilter(written)
    val expected = written.compile(ExpressionContext.None).toStyleJson()
    assertEquals("bg-base" to expected, binding.filters.last())

    handle.setFilter(nil())
    assertEquals("bg-base" to (JsonNull as JsonElement), binding.filters.last())
    assertNull(
      assertNotNull(state.layers["bg-base"]).property("filter"),
      "passing nil clears the filter",
    )

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun a_write_through_a_handle_from_a_replaced_style_throws() = runTest {
    val state = mapState()
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(
      adapter,
      OpRecordingStyleBinding(baseLayers = listOf(BackgroundLayerDescriptor("bg-base"))),
    )
    testScheduler.advanceUntilIdle()
    val stale = assertNotNull(state.layers["bg-base"])
    stale.visible = false

    // A base style load replaces the binding underneath the handle.
    state.callbacks.onStyleChanged(
      adapter,
      OpRecordingStyleBinding(baseLayers = listOf(BackgroundLayerDescriptor("bg-base"))),
    )
    testScheduler.advanceUntilIdle()

    val error = assertFailsWith<IllegalStateException> { stale.visible = true }
    assertEquals(
      true,
      "fresh handle" in error.message.orEmpty(),
      "the error must point at MapState.layers: ${error.message}",
    )
    assertNotNull(state.layers["bg-base"], "the reloaded style serves a fresh handle").visible =
      true

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun a_filter_write_on_a_composition_owned_layer_throws() = runTest {
    val state = mapState()
    state.setStyleComposition { BackgroundLayer(id = "comp-layer") }
    attach(state, OpRecordingStyleBinding())

    val handle = assertNotNull(state.layers["comp-layer"])
    assertFailsWith<IllegalStateException> { handle.setFilter(const(true)) }

    state.close()
    testScheduler.advanceUntilIdle()
  }
}
