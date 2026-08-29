package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.LineLayerDescriptor
import org.maplibre.compose.sources.VectorSource

class StyleDesiredStateSyncTest {

  private fun vectorSource(id: String) = VectorSource(id, "https://example.invalid/{z}/{x}/{y}.pbf")

  @Test
  fun reordering_replace_anchored_layers_never_restores_the_replaced_layer() {
    val baseSource = vectorSource("base-source")
    val target = LineLayerDescriptor("target", baseSource)
    val binding =
      OpRecordingStyleBinding(baseSources = listOf(baseSource), baseLayers = listOf(target))
    val node = StyleNode(binding, null)
    val anchor = Anchor.Replace("target")
    val x = LayerNode(LineLayerDescriptor("x", baseSource), anchor)
    val y = LayerNode(LineLayerDescriptor("y", baseSource), anchor)

    node.insertLayer(x, 0)
    node.insertLayer(y, 1)
    applyStyleRevision(node)
    assertEquals(listOf("x", "y"), binding.getLayers().map(Layer::id))
    assertNull(binding.getLayer("target"))
    binding.ops.clear()

    node.children.removeAt(0)
    node.children.add(1, x)
    applyStyleRevision(node)

    assertEquals(listOf("moveLayer:x"), binding.ops.toList())
    assertEquals(listOf("y", "x"), binding.getLayers().map(Layer::id))
    assertNull(binding.getLayer("target"))
  }

  @Test
  fun a_same_id_source_swap_removes_the_old_instance_before_adding_the_new() {
    val binding = OpRecordingStyleBinding()
    val node = StyleNode(binding, null)
    val a = vectorSource("shared")
    node.sourceManager.addReference(a)
    applyStyleRevision(node)

    val b = vectorSource("shared")
    node.sourceManager.removeReference(a)
    node.sourceManager.addReference(b)
    applyStyleRevision(node)

    assertEquals(
      listOf("addSource:shared", "removeSource:shared", "addSource:shared"),
      binding.ops.toList(),
    )
    assertSame(b, binding.getSource("shared"))
  }

  @Test
  fun an_anchor_naming_a_composition_layer_defers_instead_of_resolving() {
    val baseSource = vectorSource("base-source")
    val base = LineLayerDescriptor("base", baseSource)
    val binding =
      OpRecordingStyleBinding(baseSources = listOf(baseSource), baseLayers = listOf(base))
    val node = StyleNode(binding, null)
    node.insertLayer(LayerNode(LineLayerDescriptor("mine", baseSource), Anchor.Top), 0)
    applyStyleRevision(node)

    node.insertLayer(
      LayerNode(LineLayerDescriptor("above-mine", baseSource), Anchor.Above("mine")),
      1,
    )
    node.insertLayer(
      LayerNode(LineLayerDescriptor("above-base", baseSource), Anchor.Above("base")),
      2,
    )
    applyStyleRevision(node)

    // "mine" is composition-owned, so its anchor never resolves; "base" is a base-style layer.
    assertNull(binding.getLayer("above-mine"))
    assertNotNull(binding.getLayer("above-base"))
  }
}
