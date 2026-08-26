package org.maplibre.compose.style

import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.FillLayerDescriptor
import org.maplibre.compose.layers.LayerNode as ComposeLayerNode
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.SourceReferenceEffect
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.FeaturesClickHandler

private fun testSource(id: String) =
  RasterSource(id, listOf("https://example.invalid/{z}/{x}/{y}.png"), TileSetOptions())

@OptIn(ExperimentalCoroutinesApi::class)
class StyleCompositionHostTest {

  private fun TestScope.testHost(rootNode: StyleNode) =
    StyleCompositionHost(
      rootNode = rootNode,
      dispatcher = StandardTestDispatcher(testScheduler),
      density = Density(1f),
      layoutDirection = LayoutDirection.Ltr,
      logger = null,
    )

  @Test
  fun initial_content_applies_off_the_caller_with_sources_before_layers() = runTest {
    val recording = OpRecordingStyleBinding()
    val rootNode = StyleNode(recording, null)
    val host = testHost(rootNode)
    val source = testSource("tiles")

    host.setContent { RasterLayer(id = "raster", source = source) }

    // setContent marshals onto the host dispatcher, so nothing has applied on the caller.
    assertTrue(recording.ops.isEmpty())

    testScheduler.advanceUntilIdle()
    assertEquals(listOf("addSource:tiles", "addLayer:raster"), recording.ops.toList())
    assertNull(host.contentError)

    // Settling adds no further ops.
    testScheduler.advanceUntilIdle()
    assertEquals(listOf("addSource:tiles", "addLayer:raster"), recording.ops.toList())

    host.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun snapshot_state_change_applies_after_host_pumped_frames() = runTest {
    val recording = OpRecordingStyleBinding()
    val rootNode = StyleNode(recording, null)
    val host = testHost(rootNode)
    val a = testSource("a")
    val b = testSource("b")
    var showSecond by mutableStateOf(false)

    host.setContent {
      RasterLayer(id = "layer-a", source = a)
      if (showSecond) RasterLayer(id = "layer-b", source = b)
    }
    testScheduler.advanceUntilIdle()
    val framesBefore = host.framesPumped
    recording.ops.clear()

    showSecond = true
    // Nothing happens until the host's coroutines run: the write observer, the apply
    // notification, and the pumped frame all ride the host dispatcher.
    assertTrue(recording.ops.isEmpty())

    testScheduler.advanceUntilIdle()
    assertNull(host.contentError)
    assertEquals(listOf("addSource:b", "addLayerAbove:layer-b"), recording.ops.toList())
    assertTrue(host.framesPumped > framesBefore, "the update rode a host-pumped frame")

    host.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun post_frame_apply_keeps_sources_before_layers_on_a_structural_update() = runTest {
    val recording = OpRecordingStyleBinding()
    val rootNode = StyleNode(recording, null)
    val host = testHost(rootNode)
    val a = testSource("a")
    val b = testSource("b")
    var showSecond by mutableStateOf(false)

    host.setContent {
      RasterLayer(id = "layer-a", source = a)
      if (showSecond) RasterLayer(id = "layer-b", source = b)
    }
    testScheduler.advanceUntilIdle()
    // Sources attach before layers: DisposableEffects run inside the composition apply, and the
    // host's explicit applyChanges runs after it.
    assertEquals(listOf("addSource:a", "addLayer:layer-a"), recording.ops.toList())
    recording.ops.clear()

    showSecond = true
    testScheduler.advanceUntilIdle()
    assertEquals(listOf("addSource:b", "addLayerAbove:layer-b"), recording.ops.toList())

    host.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun teardown_disposes_content_and_stops_everything() = runTest {
    val recording = OpRecordingStyleBinding()
    val rootNode = StyleNode(recording, null)
    val host = testHost(rootNode)
    val source = testSource("tiles")
    var minZoom by mutableStateOf(1f)

    host.setContent { RasterLayer(id = "raster", source = source, minZoom = minZoom) }
    testScheduler.advanceUntilIdle()
    recording.ops.clear()

    host.close()
    testScheduler.advanceUntilIdle()

    // dispose ran the applier removals and the DisposableEffect disposals, layers before sources.
    assertEquals(listOf("removeLayer:raster", "removeSource:tiles"), recording.ops.toList())
    assertEquals(Recomposer.State.ShutDown, host.recomposer.currentState.value)

    // A later state write reaches nothing: observer disposed, recomposer shut down.
    val framesAfterClose = host.framesPumped
    minZoom = 5f
    testScheduler.advanceUntilIdle()
    assertEquals(framesAfterClose, host.framesPumped)

    // close is safe to call twice.
    host.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun a_second_set_content_replaces_the_previous_composition() = runTest {
    val recording = OpRecordingStyleBinding()
    val rootNode = StyleNode(recording, null)
    val host = testHost(rootNode)
    val first = testSource("first")
    val second = testSource("second")

    host.setContent { RasterLayer(id = "layer-first", source = first) }
    testScheduler.advanceUntilIdle()
    assertEquals(listOf("addSource:first", "addLayer:layer-first"), recording.ops.toList())
    recording.ops.clear()

    host.setContent { RasterLayer(id = "layer-second", source = second) }
    testScheduler.advanceUntilIdle()
    // The old composition left the style before the new one composed into it.
    assertEquals(
      listOf(
        "removeLayer:layer-first",
        "removeSource:first",
        "addSource:second",
        "addLayer:layer-second",
      ),
      recording.ops.toList(),
    )

    host.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun a_structural_toggle_with_no_source_effect_still_syncs() = runTest {
    val recording = OpRecordingStyleBinding()
    val rootNode = StyleNode(recording, null)
    val host = testHost(rootNode)
    var show by mutableStateOf(false)

    host.setContent { if (show) BackgroundLayer(id = "bg") }
    testScheduler.advanceUntilIdle()
    assertTrue(recording.ops.isEmpty())

    show = true
    testScheduler.advanceUntilIdle()
    assertEquals(listOf("addLayer:bg"), recording.ops.toList())

    show = false
    testScheduler.advanceUntilIdle()
    assertEquals(listOf("addLayer:bg", "removeLayer:bg"), recording.ops.toList())
    assertNull(host.contentError)

    host.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun the_click_routing_snapshot_reflects_the_tree_after_sync() = runTest {
    val recording = OpRecordingStyleBinding()
    val rootNode = StyleNode(recording, null)
    val host = testHost(rootNode)
    val source = testSource("tiles")
    val handler: FeaturesClickHandler = { ClickResult.Consume }
    var showSecond by mutableStateOf(false)

    host.setContent {
      SourceReferenceEffect(source)
      ComposeLayerNode(
        factory = { FillLayerDescriptor("a", source) },
        update = {},
        onClick = handler,
        onLongClick = null,
      )
      if (showSecond) {
        ComposeLayerNode(
          factory = { FillLayerDescriptor("b", source) },
          update = {},
          onClick = null,
          onLongClick = null,
        )
      }
    }
    testScheduler.advanceUntilIdle()
    assertEquals(listOf("a"), rootNode.clickRoutes.map { it.layerId })
    assertSame(handler, rootNode.clickRoutes.single().onClick)

    showSecond = true
    testScheduler.advanceUntilIdle()
    // Topmost drawn layer first, each with the handlers the composition holds now.
    assertEquals(listOf("b", "a"), rootNode.clickRoutes.map { it.layerId })
    assertNull(rootNode.clickRoutes.first { it.layerId == "b" }.onClick)
    assertSame(handler, rootNode.clickRoutes.first { it.layerId == "a" }.onClick)

    host.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun an_ordinary_apply_failure_is_recorded_and_logged_not_rethrown() = runTest {
    val recording =
      object : OpRecordingStyleBinding() {
        override fun addSource(source: Source) = throw RuntimeException("engine refused")
      }
    val rootNode = StyleNode(recording, null)
    val host = testHost(rootNode)

    host.setContent { RasterLayer(id = "raster", source = testSource("tiles")) }
    testScheduler.advanceUntilIdle()

    assertIs<RuntimeException>(host.contentError)

    host.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun cancellation_is_not_swallowed_as_a_content_error() = runTest {
    val recording =
      object : OpRecordingStyleBinding() {
        override fun addSource(source: Source) = throw CancellationException("cancelled")
      }
    val rootNode = StyleNode(recording, null)
    val host = testHost(rootNode)

    host.setContent { RasterLayer(id = "raster", source = testSource("tiles")) }
    testScheduler.advanceUntilIdle()

    assertNull(host.contentError)

    host.close()
    testScheduler.advanceUntilIdle()
  }

  @Test
  fun property_update_reaches_the_layer_without_structural_ops() = runTest {
    val recording = OpRecordingStyleBinding()
    val rootNode = StyleNode(recording, null)
    val host = testHost(rootNode)
    val source = testSource("tiles")
    var minZoom by mutableStateOf(1f)

    host.setContent { RasterLayer(id = "raster", source = source, minZoom = minZoom) }
    testScheduler.advanceUntilIdle()
    recording.ops.clear()

    minZoom = 7f
    testScheduler.advanceUntilIdle()
    // A property change updates the retained Layer object; it is not a structural style op.
    assertEquals(7f, (recording.getLayer("raster") ?: error("missing")).minZoom)
    assertTrue(recording.ops.isEmpty(), "no structural ops for a property change: ${recording.ops}")

    host.close()
    testScheduler.advanceUntilIdle()
  }
}
