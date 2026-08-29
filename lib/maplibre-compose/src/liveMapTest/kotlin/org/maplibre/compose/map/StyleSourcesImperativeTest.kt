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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.BackgroundLayerDescriptor
import org.maplibre.compose.layers.FillLayerDescriptor
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.Source
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
      hostDispatcher = StandardTestDispatcher(testScheduler),
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
    state.setStyleComposition {
      if (show) RasterLayer(id = "comp-layer", source = compositionSource)
    }
    val binding = OpRecordingStyleBinding()
    attach(state, binding)

    state.sources.add(testSource("app-src"))

    repeat(3) {
      show = !show
      state.host.awaitPendingWork()
    }
    state.clearStyleComposition()
    state.host.awaitPendingWork()

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
    state.setStyleComposition {
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
    state.setStyleComposition {
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
    state.host.awaitPendingWork()
    assertTrue(errors.isNotEmpty(), "the composition's claim of an app-owned id is reported")
    assertSame(appSource, state.sources["app-src"])

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun an_add_colliding_with_a_base_source_names_the_base_style() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    val binding = OpRecordingStyleBinding(baseSources = listOf(testSource("base-src")))
    attach(state, binding)

    val attempted = testSource("base-src")
    val error = assertFailsWith<IllegalArgumentException> { state.sources.add(attempted) }
    assertTrue("base style" in error.message.orEmpty(), "names the owner: ${error.message}")
    assertNotSame(attempted, state.sources["base-src"], "a refused add does not claim the id")
    assertNotNull(state.sources["base-src"], "the base source remains readable")

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun ids_and_ownership_reflect_each_op_without_recomposition() = runTest {
    val state = mapState()
    state.setStyleComposition {}
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
    state.setStyleComposition { RasterLayer(id = "comp-layer", source = compositionSource) }
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
    state.setStyleComposition {}
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
  fun a_reload_serves_fresh_descriptors_and_a_close_empties_the_collections() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    fun binding() =
      OpRecordingStyleBinding(
        baseSources = listOf(testSource("base-src")),
        baseLayers = listOf(BackgroundLayerDescriptor("bg")),
      )
    val first = binding()
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    state.callbacks.onStyleChanged(adapter, first)
    testScheduler.advanceUntilIdle()
    val stale = assertNotNull(state.sources["base-src"])

    // The second style declares the same source id.
    first.unload()
    state.callbacks.onStyleChanged(adapter, binding())
    testScheduler.advanceUntilIdle()
    val fresh = assertNotNull(state.sources["base-src"])
    assertNotSame(stale, fresh, "a reloaded style serves a fresh source descriptor")

    state.close()
    testScheduler.advanceUntilIdle()
    assertTrue(state.sources.ids.isEmpty(), "a closed state reports no sources")
    assertTrue(state.layers.ids.isEmpty(), "a closed state reports no layers")
  }

  @Test
  fun a_base_style_reload_drops_every_imperative_mutation() = runTest {
    val state = mapState()
    state.setStyleComposition {}
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
    assertNull(state.sources["app-src"])
    assertEquals(0, second.ops.count { it.startsWith("addSource:app-src") })
    assertEquals(0f, assertNotNull(state.layers["bg"]).minZoom, "the write dropped with the style")

    // The reloaded style takes the source again, the reapplication path.
    state.sources.add(testSource("app-src"))
    assertTrue("app-src" in state.sources.ids)

    state.close()
    testScheduler.advanceUntilIdle()
  }

  /** Starts [block] on the caller so its host op is queued before this returns. */
  private fun <T> TestScope.startOp(block: suspend () -> T): Deferred<Result<T>> =
    async(
      context = UnconfinedTestDispatcher(testScheduler),
      start = CoroutineStart.UNDISPATCHED,
    ) {
      runCatching { block() }
    }

  @Test
  fun an_op_held_across_a_binding_swap_throws_and_corrupts_nothing() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    val first = OpRecordingStyleBinding()
    state.callbacks.onStyleChanged(adapter, first)
    testScheduler.advanceUntilIdle()

    // The op is queued on the host, then the binding swaps before the host runs it.
    val held = startOp { state.sources.add(testSource("app-src")) }
    first.unload()
    state.callbacks.onStyleChanged(adapter, null)
    testScheduler.advanceUntilIdle()

    assertIs<IllegalStateException>(held.await().exceptionOrNull())
    assertNull(state.sources["app-src"], "the held op recorded nothing")
    assertEquals(0, first.ops.count { it == "addSource:app-src" })

    // The state is not corrupted: the next loaded style takes the source.
    state.callbacks.onStyleChanged(adapter, OpRecordingStyleBinding())
    testScheduler.advanceUntilIdle()
    state.sources.add(testSource("app-src"))
    assertNotNull(state.sources["app-src"])

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun close_racing_an_in_flight_op_throws_and_corrupts_nothing() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    attach(state, OpRecordingStyleBinding())

    // The op suspends on its host await, then close lands before the host runs it.
    val held = startOp { state.sources.add(testSource("app-src")) }
    state.close()
    testScheduler.advanceUntilIdle()

    // Two legal refusals race here: the host's teardown queues behind the held op, so the op
    // usually runs first and hits the unloaded-binding check; a cancellation instead maps to the
    // closed message. Both are the contracted IllegalStateException.
    assertIs<IllegalStateException>(held.await().exceptionOrNull())
    assertNull(state.sources["app-src"], "the held op recorded nothing")
    assertFailsWith<IllegalStateException> { state.sources.add(testSource("late")) }
  }

  @Test
  fun a_removal_serialized_behind_the_layer_installing_sync_throws() = runTest {
    val state = mapState()
    val compositionSource = testSource("comp-src")
    state.setStyleComposition { RasterLayer(id = "comp-layer", source = compositionSource) }
    val adapter = FakeMapAdapter()
    state.attachSession(adapter)
    val binding = OpRecordingStyleBinding()
    state.callbacks.onStyleChanged(adapter, binding)

    // The removal is queued behind the sync that installs the layer drawing from the source.
    val held = startOp { state.sources.remove("comp-src") }
    testScheduler.advanceUntilIdle()

    val error = assertIs<IllegalStateException>(held.await().exceptionOrNull())
    assertTrue("composition" in error.message.orEmpty(), "names the owner: ${error.message}")
    assertTrue(binding.layerExists("comp-layer"), "the refused removal keeps the layer")
    assertSame(compositionSource, state.sources["comp-src"])

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun a_removal_racing_a_live_layer_install_hits_the_in_use_guard() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    val binding = OpRecordingStyleBinding()
    attach(state, binding)
    state.sources.add(testSource("app-src"))

    // The removal queues on the host, then a live layer starts drawing the source before it runs,
    // so the in-use guard itself, not ownership, is what refuses.
    val held = startOp { state.sources.remove("app-src") }
    binding.addLayer(FillLayerDescriptor("live-layer", testSource("app-src")))
    testScheduler.advanceUntilIdle()

    val error = assertIs<IllegalStateException>(held.await().exceptionOrNull())
    assertTrue("live-layer" in error.message.orEmpty(), "names the layer: ${error.message}")
    assertNotNull(state.sources["app-src"], "the refused removal keeps the source")

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun ops_on_a_detached_or_closed_state_throw_and_corrupt_nothing() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    testScheduler.advanceUntilIdle()

    assertFailsWith<IllegalStateException> { state.sources.add(testSource("app-src")) }
    assertFailsWith<IllegalStateException> { state.sources.remove("app-src") }
    assertNull(state.sources["app-src"])

    // The state is not corrupted: a style that loads later takes the source.
    attach(state, OpRecordingStyleBinding())
    state.sources.add(testSource("app-src"))
    assertNotNull(state.sources["app-src"])

    state.close()
    testScheduler.advanceUntilIdle()
    assertFailsWith<IllegalStateException> { state.sources.add(testSource("late")) }
    assertFailsWith<IllegalStateException> { state.sources.remove("app-src") }
  }

  @Test
  fun a_refused_platform_add_does_not_publish_ownership() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    val binding =
      object : OpRecordingStyleBinding() {
        override fun addSource(source: Source): Boolean {
          op("addSource:${source.id}")
          error("engine refused ${source.id}")
        }
      }
    attach(state, binding)

    val error = assertFailsWith<IllegalStateException> { state.sources.add(testSource("app-src")) }
    assertTrue("engine refused" in error.message.orEmpty(), error.message)
    assertNull(state.sources["app-src"], "ownership publishes only after the engine accepts")
    assertEquals(listOf("addSource:app-src"), binding.ops.filter { it.startsWith("addSource") })

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun an_unloaded_add_does_not_publish_ownership() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    val binding =
      object : OpRecordingStyleBinding() {
        override fun addSource(source: Source): Boolean {
          op("addSource:${source.id}")
          return false
        }
      }
    attach(state, binding)

    val error = assertFailsWith<IllegalStateException> { state.sources.add(testSource("app-src")) }
    assertTrue("unloaded" in error.message.orEmpty(), error.message)
    assertNull(state.sources["app-src"], "a dropped install must not publish ownership")

    state.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun a_source_already_owned_by_another_state_is_refused() = runTest {
    val first = mapState()
    val second = mapState()
    first.setStyleComposition {}
    second.setStyleComposition {}
    attach(first, OpRecordingStyleBinding())
    attach(second, OpRecordingStyleBinding())
    val source = testSource("shared")
    first.sources.add(source)
    val error = assertFailsWith<IllegalArgumentException> { second.sources.add(source) }
    assertTrue("another MapState" in error.message.orEmpty(), error.message)
    assertSame(source, first.sources["shared"])
    assertNull(second.sources["shared"])
    first.close()
    second.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun an_unchanged_geojson_setData_does_not_write_the_engine() = runTest {
    val state = mapState()
    state.setStyleComposition {}
    val binding = OpRecordingStyleBinding()
    attach(state, binding)
    val data = GeoJsonData.Uri("https://example.invalid/a.json")
    val source = GeoJsonSource("geo", data, GeoJsonOptions())
    state.sources.add(source)
    val writes = binding.ops.count { it.startsWith("addSource") }
    source.setData(data)
    assertEquals(writes, binding.ops.count { it.startsWith("addSource") })
    source.setData(GeoJsonData.Uri("https://example.invalid/b.json"))
    assertEquals(GeoJsonData.Uri("https://example.invalid/b.json"), source.data)

    state.close()
    testScheduler.advanceUntilIdle()
  }
}
