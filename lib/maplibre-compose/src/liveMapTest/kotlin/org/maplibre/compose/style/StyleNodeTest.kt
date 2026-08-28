package org.maplibre.compose.style

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.LineLayerDescriptor
import org.maplibre.compose.map.FakeMapAdapter
import org.maplibre.compose.map.MapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.VectorSource
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

@OptIn(ExperimentalTestApi::class)
class StyleNodeTest {
  private val testSources by lazy {
    listOf(
      VectorSource("foo", "https://example.com/{z}/{x}/{y}.pbf"),
      GeoJsonSource("bar", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions()),
      GeoJsonSource("baz", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions()),
    )
  }

  private val testLayers by lazy {
    listOf(
      LineLayerDescriptor("foo", testSources[0]),
      LineLayerDescriptor("bar", testSources[1]),
      LineLayerDescriptor("baz", testSources[2]),
    )
  }

  private fun makeStyleNode(): StyleNode {
    return StyleNode(
      RecordingStyleBinding(baseSources = testSources, baseLayers = testLayers),
      null,
    )
  }

  /**
   * A state whose record points at [style], so [MapState.sources] snapshots the live binding the
   * record owns.
   */
  private fun mapStateOver(style: StyleBinding): MapState {
    val mapState = MapState()
    val adapter = FakeMapAdapter()
    mapState.attachSession(adapter)
    mapState.callbacks.onStyleChanged(adapter, style)
    return mapState
  }

  private fun vectorSource(id: String, attribution: String): VectorSource =
    VectorSource(
      id = id,
      tiles = listOf("https://example.com/{z}/{x}/{y}.pbf"),
      options = TileSetOptions(attributionHtml = attribution),
    )

  @BeforeTest
  fun platformSetup() {
    prepareStyleNodeTestHost()
  }

  @Test
  fun gets_a_base_source_by_its_exact_id() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      assertEquals(testSources[1], s.sourceManager.getBaseSource("bar"))
      assertEquals(null, s.sourceManager.getBaseSource("BAR"))
    }
  }

  @Test
  fun adds_a_user_source() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val newSource =
        GeoJsonSource("new", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
      s.sourceManager.addReference(newSource)
      s.applyChanges()
      assertEquals(4, s.binding.getSources().size)
      assertEquals(newSource, s.binding.getSource("new"))
    }
  }

  @Test
  fun removes_a_user_source() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val newSource =
        GeoJsonSource("new", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
      s.sourceManager.addReference(newSource)
      s.applyChanges()
      s.sourceManager.removeReference(newSource)
      s.applyChanges()
      assertEquals(3, s.binding.getSources().size)
      assertNull(s.binding.getSource("new"))
    }
  }

  @Test
  fun unchanged_source_state_preserves_the_snapshot() = runComposeUiTest {
    runOnUiThread {
      val source = vectorSource("source", "same")
      val style = RecordingStyleBinding(baseSources = listOf(source))
      val state = mapStateOver(style)
      try {
        val previousSources = state.sources.snapshot

        style.replaceSource(vectorSource("source", "same"))
        state.sources.refreshSource("source")

        assertSame(previousSources, state.sources.snapshot)
        assertSame(source, state.sources.snapshot["source"])
      } finally {
        state.close()
      }
    }
  }

  @Test
  fun changed_source_state_replaces_only_the_affected_source() = runComposeUiTest {
    runOnUiThread {
      val source = vectorSource("source", "attribution")
      val stable = vectorSource("stable", "stable attribution")
      val style = RecordingStyleBinding(baseSources = listOf(source, stable))
      val state = mapStateOver(style)
      try {
        val previousSources = state.sources.snapshot
        val replacement =
          RasterSource(
            id = "source",
            tiles = listOf("https://example.com/{z}/{x}/{y}.png"),
            options = TileSetOptions(attributionHtml = "changed attribution"),
          )

        style.replaceSource(replacement)
        state.sources.refreshSource("source")

        assertNotSame(previousSources, state.sources.snapshot)
        assertSame(replacement, state.sources.snapshot["source"])
        assertSame(stable, state.sources.snapshot["stable"])
      } finally {
        state.close()
      }
    }
  }

  @Test
  fun a_missing_source_is_removed_from_the_state() = runComposeUiTest {
    runOnUiThread {
      val source = vectorSource("source", "attribution")
      val style = RecordingStyleBinding(baseSources = listOf(source))
      val state = mapStateOver(style)
      try {
        style.removeSource(source)
        state.sources.refreshSource("source")

        assertNull(state.sources.snapshot["source"])
      } finally {
        state.close()
      }
    }
  }

  @Test
  fun an_unloaded_style_ignores_source_callbacks() = runComposeUiTest {
    runOnUiThread {
      val source = vectorSource("source", "attribution")
      val style = RecordingStyleBinding(baseSources = listOf(source))
      val state = mapStateOver(style)
      try {
        val previousSources = state.sources.snapshot

        style.unload()
        style.removeSource(source)
        state.sources.refreshSource("source")
        state.sources.refreshSources()

        assertSame(previousSources, state.sources.snapshot)
        assertSame(source, state.sources.snapshot["source"])
      } finally {
        state.close()
      }
    }
  }

  @Test
  fun a_user_source_may_not_replace_a_base_source() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      assertFails {
        s.sourceManager.addReference(
          GeoJsonSource("foo", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())
        )
      }
    }
  }

  @Test
  fun a_base_source_may_not_be_removed() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      assertFails { s.sourceManager.removeReference(testSources[1]) }
    }
  }

  @Test
  fun anchor_top_places_layers_over_the_base_style() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val nodes =
        (0..2).map { LayerNode(LineLayerDescriptor("new$it", testSources[0]), Anchor.Top) }
      nodes.forEachIndexed { i, node -> s.insertLayer(node, i) }
      s.applyChanges()
      assertEquals(
        listOf("foo", "bar", "baz", "new0", "new1", "new2"),
        s.binding.getLayers().map(Layer::id),
      )
    }
  }

  @Test
  fun anchor_bottom_places_layers_under_the_base_style() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val nodes =
        (0..2).map { LayerNode(LineLayerDescriptor("new$it", testSources[0]), Anchor.Bottom) }
      nodes.forEachIndexed { i, node -> s.insertLayer(node, i) }
      s.applyChanges()
      assertEquals(
        listOf("new0", "new1", "new2", "foo", "bar", "baz"),
        s.binding.getLayers().map(Layer::id),
      )
    }
  }

  @Test
  fun anchor_above_places_layers_over_the_named_layer() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val nodes =
        (0..2).map { LayerNode(LineLayerDescriptor("new$it", testSources[0]), Anchor.Above("foo")) }
      nodes.forEachIndexed { i, node -> s.insertLayer(node, i) }
      s.applyChanges()
      assertEquals(
        listOf("foo", "new0", "new1", "new2", "bar", "baz"),
        s.binding.getLayers().map(Layer::id),
      )
    }
  }

  @Test
  fun anchor_below_places_layers_under_the_named_layer() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val nodes =
        (0..2).map { LayerNode(LineLayerDescriptor("new$it", testSources[0]), Anchor.Below("baz")) }
      nodes.forEachIndexed { i, node -> s.insertLayer(node, i) }
      s.applyChanges()
      assertEquals(
        listOf("foo", "bar", "new0", "new1", "new2", "baz"),
        s.binding.getLayers().map(Layer::id),
      )
    }
  }

  @Test
  fun anchor_replace_takes_the_place_of_the_named_layer() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val nodes =
        (0..2).map {
          LayerNode(LineLayerDescriptor("new$it", testSources[0]), Anchor.Replace("bar"))
        }
      nodes.forEachIndexed { i, node -> s.insertLayer(node, i) }
      s.applyChanges()
      assertEquals(
        listOf("foo", "new0", "new1", "new2", "baz"),
        s.binding.getLayers().map(Layer::id),
      )
    }
  }

  @Test
  fun removing_every_replacement_restores_the_replaced_layer() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val nodes =
        (0..2).map {
          LayerNode(LineLayerDescriptor("new$it", testSources[0]), Anchor.Replace("bar"))
        }

      nodes.forEachIndexed { i, node -> s.insertLayer(node, i) }
      s.applyChanges()

      assertEquals(
        listOf("foo", "new0", "new1", "new2", "baz"),
        s.binding.getLayers().map(Layer::id),
      )

      repeat(nodes.size) { s.removeLayerAt(0) }
      s.applyChanges()

      assertEquals(listOf("foo", "bar", "baz"), s.binding.getLayers().map(Layer::id))
    }
  }

  @Test
  fun recreating_a_replacement_after_an_unload_leaves_the_style_alone() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val oldNode = LayerNode(LineLayerDescriptor("old", testSources[0]), Anchor.Replace("bar"))
      val newNode = LayerNode(LineLayerDescriptor("new", testSources[0]), Anchor.Replace("bar"))

      s.insertLayer(oldNode, 0)
      s.applyChanges()
      (s.binding as RecordingStyleBinding).unload()

      s.insertLayer(newNode, 0)
      s.removeLayerAt(1)
      s.applyChanges()
      s.removeLayerAt(0)

      // An unloaded style takes no mutations, so it keeps the layers the last loaded sync left.
      assertEquals(listOf("foo", "old", "baz"), s.binding.getLayers().map(Layer::id))
    }
  }

  @Test
  fun a_same_id_layer_swap_adds_before_it_removes() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val l1 = LayerNode(LineLayerDescriptor("new", testSources[0]), Anchor.Top)
      val l2 = LayerNode(LineLayerDescriptor("new", testSources[1]), Anchor.Top)

      s.insertLayer(l1, 0)
      s.applyChanges()

      assertEquals(l1.layer, s.binding.getLayer("new"))

      s.insertLayer(l2, 0)
      s.removeLayerAt(1)
      s.applyChanges()

      assertEquals(l2.layer, s.binding.getLayer("new"))
    }
  }

  @Test
  fun repeated_anchors_merge_into_one_group() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()

      s.insertLayer(LayerNode(LineLayerDescriptor("b1", testSources[0]), Anchor.Bottom), 0)
      s.insertLayer(LayerNode(LineLayerDescriptor("t1", testSources[0]), Anchor.Top), 0)
      s.applyChanges()

      assertEquals(listOf("b1", "foo", "bar", "baz", "t1"), s.binding.getLayers().map(Layer::id))

      s.insertLayer(LayerNode(LineLayerDescriptor("b2", testSources[0]), Anchor.Bottom), 0)
      s.insertLayer(LayerNode(LineLayerDescriptor("t2", testSources[0]), Anchor.Top), 0)
      s.applyChanges()

      assertEquals(
        listOf("b2", "b1", "foo", "bar", "baz", "t2", "t1"),
        s.binding.getLayers().map(Layer::id),
      )
    }
  }
}
