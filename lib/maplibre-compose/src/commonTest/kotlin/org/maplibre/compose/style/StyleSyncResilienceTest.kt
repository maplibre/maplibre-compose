package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

class StyleSyncResilienceTest {

  private fun vectorSource(id: String) = VectorSource(id, "https://example.invalid/{z}/{x}/{y}.pbf")

  @Test
  fun a_failed_layer_add_resumes_without_duplicating_earlier_adds() {
    val baseSource = vectorSource("base-source")
    val base = LineLayerDescriptor("base", baseSource)
    val binding = FlakyStyleBinding(baseSources = listOf(baseSource), baseLayers = listOf(base))
    val node = StyleNode(binding, null)
    node.insertLayer(LayerNode(LineLayerDescriptor("l1", baseSource), Anchor.Top), 0)
    node.insertLayer(LayerNode(LineLayerDescriptor("l2", baseSource), Anchor.Top), 1)

    binding.failOnOpNumber = 2
    assertFails { applyStyleRevision(node) }
    assertEquals(listOf("addLayer:l1"), binding.ops.toList())

    applyStyleRevision(node)

    // The exact pair: the resumed sync adds only what the failed one had left to do.
    assertEquals(listOf("addLayer:l1", "addLayerAbove:l2"), binding.ops.toList())
    assertEquals(listOf("base", "l1", "l2"), binding.getLayers().map(Layer::id))
  }

  @Test
  fun a_failed_source_add_is_retried_on_the_next_sync() {
    val binding = FlakyStyleBinding()
    val node = StyleNode(binding, null)
    val first = vectorSource("first")
    val second = vectorSource("second")
    node.sourceManager.addReference(first)
    node.sourceManager.addReference(second)

    binding.failOnOpNumber = 2
    assertFails { applyStyleRevision(node) }
    assertNull(binding.getSource("second"))

    applyStyleRevision(node)

    assertEquals(1, binding.ops.count { it == "addSource:second" }, "the retry ran once")
    assertEquals(1, binding.ops.count { it == "addSource:first" }, "the earlier add did not repeat")
    assertNotNull(binding.getSource("second"))
    assertNotNull(binding.getSource("first"))
  }

  @Test
  fun a_failed_layer_removal_resumes_without_forgetting_the_layer() {
    val baseSource = vectorSource("base-source")
    val base = LineLayerDescriptor("base", baseSource)
    val binding = FlakyStyleBinding(baseSources = listOf(baseSource), baseLayers = listOf(base))
    val node = StyleNode(binding, null)
    val l1 = LayerNode(LineLayerDescriptor("l1", baseSource), Anchor.Top)
    node.insertLayer(l1, 0)
    applyStyleRevision(node)

    node.children.remove(l1)
    binding.failOnOpNumber = 2
    assertFails { applyStyleRevision(node) }
    assertNotNull(binding.getLayer("l1"))

    applyStyleRevision(node)

    assertEquals(1, binding.ops.count { it == "removeLayer:l1" }, "the retry removed it once")
    assertEquals(1, binding.ops.count { it == "addLayer:l1" }, "the earlier add did not repeat")
    assertEquals(listOf("base"), binding.getLayers().map(Layer::id))
  }

  @Test
  fun a_failed_replace_removal_is_retried_instead_of_keeping_both_layers() {
    val baseSource = vectorSource("base-source")
    val base = LineLayerDescriptor("base", baseSource)
    val binding = FlakyStyleBinding(baseSources = listOf(baseSource), baseLayers = listOf(base))
    val node = StyleNode(binding, null)
    val replacement = LayerNode(LineLayerDescriptor("mine", baseSource), Anchor.Replace("base"))
    node.insertLayer(replacement, 0)

    // Op 1 adds the replacement; op 2 is the original's removal.
    binding.failOnOpNumber = 2
    assertFails { applyStyleRevision(node) }
    assertNotNull(binding.getLayer("base"))
    assertNotNull(binding.getLayer("mine"))

    applyStyleRevision(node)

    assertEquals(1, binding.ops.count { it == "removeLayer:base" }, "the retry removed it once")
    assertEquals(
      1,
      binding.ops.count { it == "addLayerAbove:mine" },
      "the replacement was not added twice",
    )
    assertEquals(listOf("mine"), binding.getLayers().map(Layer::id))

    // The finished replace still restores the original when the replacement leaves.
    node.children.remove(replacement)
    applyStyleRevision(node)
    assertEquals(listOf("base"), binding.getLayers().map(Layer::id))
  }

  @Test
  fun an_abandoned_replace_with_a_pending_removal_keeps_the_original() {
    val baseSource = vectorSource("base-source")
    val base = LineLayerDescriptor("base", baseSource)
    val binding = FlakyStyleBinding(baseSources = listOf(baseSource), baseLayers = listOf(base))
    val node = StyleNode(binding, null)
    val replacement = LayerNode(LineLayerDescriptor("mine", baseSource), Anchor.Replace("base"))
    node.insertLayer(replacement, 0)

    binding.failOnOpNumber = 2
    assertFails { applyStyleRevision(node) }

    // The replacement leaves before the original's removal ever succeeds.
    node.children.remove(replacement)
    applyStyleRevision(node)

    assertEquals(1, binding.ops.count { it == "removeLayer:mine" }, "the replacement left once")
    assertEquals(
      0,
      binding.ops.count { it == "removeLayer:base" },
      "the abandoned replace must never remove the original",
    )
    assertEquals(listOf("base"), binding.getLayers().map(Layer::id))
  }
}
