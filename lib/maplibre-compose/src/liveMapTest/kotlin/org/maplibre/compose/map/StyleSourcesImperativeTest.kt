package org.maplibre.compose.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.BackgroundLayerDescriptor
import org.maplibre.compose.layers.FillLayerDescriptor
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.style.OpRecordingStyleBinding
import org.maplibre.compose.style.collectStyleErrors

private fun testSource(id: String) =
  RasterSource(id, listOf("https://example.invalid/{z}/{x}/{y}.png"), TileSetOptions())

@OptIn(ExperimentalCoroutinesApi::class)
class StyleSourcesImperativeTest {

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
  fun the_sync_never_removes_or_readds_an_imperative_source() = runTest {
    val state = mapState()
    var show by mutableStateOf(true)
    val compositionSource = testSource("comp-src")
    state.setStyleContent { if (show) RasterLayer(id = "comp-layer", source = compositionSource) }
    val binding = OpRecordingStyleBinding()
    attach(state, binding)

    state.sources.add(testSource("app-src"))

    repeat(3) {
      show = !show
      Snapshot.sendApplyNotifications()
      testScheduler.advanceUntilIdle()
    }
    state.clearStyleContent()
    Snapshot.sendApplyNotifications()
    testScheduler.advanceUntilIdle()

    assertEquals(1, binding.ops.count { it == "addSource:app-src" })
    assertEquals(0, binding.ops.count { it == "removeSource:app-src" })
    assertNotNull(state.sources["app-src"], "the imperative source survives the content teardown")

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun a_reorder_loop_and_an_imperative_loop_end_with_no_errors() = runTest {
    val state = mapState()
    val errors = collectStyleErrors(state.host)
    var reversed by mutableStateOf(false)
    state.setStyleContent {
      val order = if (reversed) listOf("b", "a") else listOf("a", "b")
      order.forEach { BackgroundLayer(id = it) }
    }
    val binding = OpRecordingStyleBinding()
    attach(state, binding)

    val reorderLoop = launch {
      repeat(10) {
        reversed = !reversed
        Snapshot.sendApplyNotifications()
        yield()
      }
    }
    val imperativeLoop = launch {
      repeat(10) {
        state.sources.add(testSource("app-src"))
        state.sources.remove("app-src")
      }
      state.sources.add(testSource("app-src"))
    }
    reorderLoop.join()
    imperativeLoop.join()
    testScheduler.advanceUntilIdle()

    assertEquals(emptyList(), errors.map { it.message })
    assertNotNull(state.sources["app-src"])
    assertTrue(binding.layerExists("a") && binding.layerExists("b"))

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun ownership_collisions_in_both_directions_fail_atomically() = runTest {
    val state = mapState()
    val errors = collectStyleErrors(state.host)
    val compositionSource = testSource("comp-src")
    var claimAppId by mutableStateOf(false)
    state.setStyleContent {
      RasterLayer(id = "comp-layer", source = compositionSource)
      if (claimAppId) RasterLayer(id = "clash-layer", source = testSource("app-src"))
    }
    attach(state, OpRecordingStyleBinding())

    // Imperative direction: the composition owns the id, and nothing is recorded.
    val error =
      assertFailsWith<IllegalArgumentException> { state.sources.add(testSource("comp-src")) }
    assertTrue("composition" in error.message.orEmpty(), "names the owner: ${error.message}")
    assertSame(compositionSource, state.sources["comp-src"])

    // Composition direction: the collision surfaces as a style error, and the app source stays.
    val appSource = testSource("app-src")
    state.sources.add(appSource)
    claimAppId = true
    Snapshot.sendApplyNotifications()
    testScheduler.advanceUntilIdle()
    assertTrue(errors.isNotEmpty(), "the composition's claim of an app-owned id is reported")
    assertSame(appSource, state.sources["app-src"])

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun an_add_colliding_with_a_base_source_names_the_base_style() = runTest {
    val state = mapState()
    state.setStyleContent {}
    val binding = OpRecordingStyleBinding(baseSources = listOf(testSource("base-src")))
    attach(state, binding)

    val error =
      assertFailsWith<IllegalArgumentException> { state.sources.add(testSource("base-src")) }
    assertTrue("already exists" in error.message.orEmpty(), "names the owner: ${error.message}")
    assertNull(state.styleNode.appSourceSnapshot["base-src"])

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun ids_and_ownership_reflect_each_op_without_recomposition() = runTest {
    val state = mapState()
    state.setStyleContent {}
    attach(state, OpRecordingStyleBinding())

    state.sources.add(testSource("app-src"))
    assertTrue("app-src" in state.sources.ids, "the add reaches ids with no recomposition")
    assertNotNull(state.sources["app-src"])

    state.sources.remove("app-src")
    assertFalse("app-src" in state.sources.ids, "the remove reaches ids with no recomposition")
    assertNull(state.sources["app-src"])

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun removing_a_missing_or_foreign_id_throws_the_contracted_exceptions() = runTest {
    val state = mapState()
    val compositionSource = testSource("comp-src")
    state.setStyleContent { RasterLayer(id = "comp-layer", source = compositionSource) }
    val binding = OpRecordingStyleBinding(baseSources = listOf(testSource("base-src")))
    attach(state, binding)

    assertFailsWith<IllegalArgumentException> { state.sources.remove("absent") }
    assertFailsWith<IllegalStateException> { state.sources.remove("comp-src") }
    assertFailsWith<IllegalStateException> { state.sources.remove("base-src") }

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun removing_a_source_a_live_layer_draws_from_throws() = runTest {
    val state = mapState()
    state.setStyleContent {}
    val binding = OpRecordingStyleBinding()
    attach(state, binding)

    val source = testSource("app-src")
    state.sources.add(source)
    binding.addLayer(FillLayerDescriptor("app-layer", source))

    val error = assertFailsWith<IllegalStateException> { state.sources.remove("app-src") }
    assertTrue("app-layer" in error.message.orEmpty(), "names the layer: ${error.message}")
    assertNotNull(state.sources["app-src"], "the refused removal keeps the source")

    binding.removeLayer(binding.getLayer("app-layer")!!)
    state.sources.remove("app-src")
    assertNull(state.sources["app-src"])

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun a_base_style_reload_drops_every_imperative_mutation() = runTest {
    val state = mapState()
    state.setStyleContent {}
    val first = OpRecordingStyleBinding(baseLayers = listOf(BackgroundLayerDescriptor("bg")))
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, first)
    testScheduler.advanceUntilIdle()

    state.sources.add(testSource("app-src"))
    val handle = assertNotNull(state.layers["bg"])
    handle.minZoom = 5f

    val second = OpRecordingStyleBinding(baseLayers = listOf(BackgroundLayerDescriptor("bg")))
    first.unload()
    state.callbacks.onStyleChanged(adapter, second)
    testScheduler.advanceUntilIdle()

    assertFalse("app-src" in state.sources.ids)
    assertNull(state.styleNode.appSourceSnapshot["app-src"])
    assertEquals(0, second.ops.count { it.startsWith("addSource:app-src") })
    assertEquals(0f, assertNotNull(state.layers["bg"]).minZoom, "the write dropped with the style")

    // The reloaded style takes the source again, the reapplication path.
    state.sources.add(testSource("app-src"))
    assertTrue("app-src" in state.sources.ids)

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun ops_on_a_detached_or_closed_state_throw_and_corrupt_nothing() = runTest {
    val state = mapState()
    state.setStyleContent {}
    testScheduler.advanceUntilIdle()

    assertFailsWith<IllegalStateException> { state.sources.add(testSource("app-src")) }
    assertFailsWith<IllegalStateException> { state.sources.remove("app-src") }
    assertTrue(state.styleNode.appSourceSnapshot.isEmpty())

    // The state is not corrupted: a style that loads later takes the source.
    attach(state, OpRecordingStyleBinding())
    state.sources.add(testSource("app-src"))
    assertNotNull(state.sources["app-src"])

    state.close()
    testScheduler.advanceUntilIdle()
    assertFailsWith<IllegalStateException> { state.sources.add(testSource("late")) }
    assertFailsWith<IllegalStateException> { state.sources.remove("app-src") }
  }
}
