package org.maplibre.compose.style

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.LineLayerDescriptor
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.VectorSource

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class StyleDesiredStateSyncTest {

  @BeforeTest
  fun platformSetup() {
    prepareStyleNodeTestHost()
  }

  private fun StyleNode.insertLayer(node: LayerNode<*>, index: Int) {
    children.add(index, node)
    onChildInserted(index, node)
  }

  @Test
  fun reordering_replace_anchored_layers_never_restores_the_replaced_layer() = runComposeUiTest {
    runOnUiThread {
      val baseSource = VectorSource("base-source", "https://example.invalid/{z}/{x}/{y}.pbf")
      val target = LineLayerDescriptor("target", baseSource)
      val binding =
        OpRecordingStyleBinding(baseSources = listOf(baseSource), baseLayers = listOf(target))
      val node = StyleNode(binding, null)
      val anchor = Anchor.Replace("target")
      val x = LayerNode(LineLayerDescriptor("x", baseSource), anchor)
      val y = LayerNode(LineLayerDescriptor("y", baseSource), anchor)

      node.insertLayer(x, 0)
      node.insertLayer(y, 1)
      node.applyChanges()
      assertEquals(listOf("x", "y"), binding.getLayers().map(Layer::id))
      assertNull(binding.getLayer("target"))
      binding.ops.clear()

      node.children.removeAt(0)
      node.children.add(1, x)
      node.applyChanges()

      assertEquals(listOf("moveLayer:x"), binding.ops.toList())
      assertEquals(listOf("y", "x"), binding.getLayers().map(Layer::id))
      assertNull(binding.getLayer("target"))
    }
  }

  @Test
  fun a_style_swap_reapplies_sources_before_layers_to_the_new_binding() = runTest {
    val first = OpRecordingStyleBinding()
    val second = OpRecordingStyleBinding()
    val rootNode = StyleNode(first, null)
    val host =
      StyleCompositionHost(
        rootNode = rootNode,
        dispatcher = StandardTestDispatcher(testScheduler),
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        logger = null,
      )
    val source =
      RasterSource("tiles", listOf("https://example.invalid/{z}/{x}/{y}.png"), TileSetOptions())

    try {
      host.setContent {
        RasterLayer(id = "layer-1", source = source)
        RasterLayer(id = "layer-2", source = source)
      }
      testScheduler.advanceUntilIdle()
      assertEquals(
        listOf("addSource:tiles", "addLayer:layer-1", "addLayerAbove:layer-2"),
        first.ops.toList(),
      )

      // The engine session unloads the outgoing style before reporting the new one.
      first.unload()
      rootNode.binding = second
      host.requestApplyChanges()
      testScheduler.advanceUntilIdle()

      assertNull(host.contentError)
      assertEquals(
        listOf("addSource:tiles", "addLayer:layer-1", "addLayerAbove:layer-2"),
        second.ops.toList(),
      )
      assertTrue(source.isAttached, "the source should be attached to the new style")
    } finally {
      host.close()
      testScheduler.advanceUntilIdle()
    }
  }
}
