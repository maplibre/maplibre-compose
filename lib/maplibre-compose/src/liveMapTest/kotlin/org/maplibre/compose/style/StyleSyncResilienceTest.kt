package org.maplibre.compose.style

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.LineLayerDescriptor
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.VectorSource

/** An [OpRecordingStyleBinding] that refuses one numbered operation, then works again. */
private class FlakyStyleBinding(
  baseSources: List<Source> = emptyList(),
  baseLayers: List<Layer> = emptyList(),
) : OpRecordingStyleBinding(baseSources = baseSources, baseLayers = baseLayers) {
  var failOnOpNumber: Int = -1
  private var opNumber = 0

  override fun op(name: String) {
    opNumber++
    if (opNumber == failOnOpNumber) throw RuntimeException("engine refused $name")
    super.op(name)
  }
}

@OptIn(ExperimentalTestApi::class)
class StyleSyncResilienceTest {

  @BeforeTest
  fun platformSetup() {
    prepareStyleNodeTestHost()
  }

  private fun vectorSource(id: String) = VectorSource(id, "https://example.invalid/{z}/{x}/{y}.pbf")

  private fun StyleNode.insertLayer(node: LayerNode<*>, index: Int) {
    children.add(index, node)
    onChildInserted(index, node)
  }

  @Test
  fun a_failed_layer_add_resumes_without_duplicating_earlier_adds() = runComposeUiTest {
    runOnUiThread {
      val baseSource = vectorSource("base-source")
      val base = LineLayerDescriptor("base", baseSource)
      val binding = FlakyStyleBinding(baseSources = listOf(baseSource), baseLayers = listOf(base))
      val node = StyleNode(binding, null)
      node.insertLayer(LayerNode(LineLayerDescriptor("l1", baseSource), Anchor.Top), 0)
      node.insertLayer(LayerNode(LineLayerDescriptor("l2", baseSource), Anchor.Top), 1)

      binding.failOnOpNumber = 2
      assertFails { node.applyChanges() }
      assertEquals(listOf("addLayer:l1"), binding.ops.toList())

      node.applyChanges()

      assertEquals(listOf("addLayer:l1", "addLayerAbove:l2"), binding.ops.toList())
      assertEquals(listOf("base", "l1", "l2"), binding.getLayers().map(Layer::id))
    }
  }

  @Test
  fun a_failed_source_add_is_retried_on_the_next_sync() = runComposeUiTest {
    runOnUiThread {
      val binding = FlakyStyleBinding()
      val node = StyleNode(binding, null)
      val first = vectorSource("first")
      val second = vectorSource("second")
      node.sourceManager.addReference(first)
      node.sourceManager.addReference(second)

      binding.failOnOpNumber = 2
      assertFails { node.applyChanges() }
      assertNull(binding.getSource("second"))

      node.applyChanges()

      assertEquals(
        listOf("addSource:first", "addSource:second"),
        binding.ops.toList(),
      )
      assertNotNull(binding.getSource("second"))
    }
  }

  @Test
  fun a_failed_layer_removal_resumes_without_forgetting_the_layer() = runComposeUiTest {
    runOnUiThread {
      val baseSource = vectorSource("base-source")
      val base = LineLayerDescriptor("base", baseSource)
      val binding = FlakyStyleBinding(baseSources = listOf(baseSource), baseLayers = listOf(base))
      val node = StyleNode(binding, null)
      val l1 = LayerNode(LineLayerDescriptor("l1", baseSource), Anchor.Top)
      node.insertLayer(l1, 0)
      node.applyChanges()

      node.children.remove(l1)
      binding.failOnOpNumber = 2
      assertFails { node.applyChanges() }
      assertNotNull(binding.getLayer("l1"))

      node.applyChanges()

      assertEquals(
        listOf("addLayer:l1", "removeLayer:l1"),
        binding.ops.toList(),
      )
      assertNull(binding.getLayer("l1"))
    }
  }

  @Test
  fun a_same_id_source_swap_removes_the_old_instance_before_adding_the_new() = runComposeUiTest {
    runOnUiThread {
      val binding = OpRecordingStyleBinding()
      val node = StyleNode(binding, null)
      val a = vectorSource("shared")
      node.sourceManager.addReference(a)
      node.applyChanges()

      val b = vectorSource("shared")
      node.sourceManager.removeReference(a)
      node.sourceManager.addReference(b)
      node.applyChanges()

      assertEquals(
        listOf("addSource:shared", "removeSource:shared", "addSource:shared"),
        binding.ops.toList(),
      )
      assertSame(b, binding.getSource("shared"))
    }
  }

  @Test
  fun an_anchor_naming_a_composition_layer_defers_instead_of_resolving() = runComposeUiTest {
    runOnUiThread {
      val baseSource = vectorSource("base-source")
      val base = LineLayerDescriptor("base", baseSource)
      val binding =
        OpRecordingStyleBinding(baseSources = listOf(baseSource), baseLayers = listOf(base))
      val node = StyleNode(binding, null)
      node.insertLayer(LayerNode(LineLayerDescriptor("mine", baseSource), Anchor.Top), 0)
      node.applyChanges()

      node.insertLayer(
        LayerNode(LineLayerDescriptor("above-mine", baseSource), Anchor.Above("mine")),
        1,
      )
      node.insertLayer(
        LayerNode(LineLayerDescriptor("above-base", baseSource), Anchor.Above("base")),
        2,
      )
      node.applyChanges()

      // "mine" is composition-owned, so its anchor never resolves; "base" is a base-style layer.
      assertNull(binding.getLayer("above-mine"))
      assertNotNull(binding.getLayer("above-base"))
    }
  }
}
