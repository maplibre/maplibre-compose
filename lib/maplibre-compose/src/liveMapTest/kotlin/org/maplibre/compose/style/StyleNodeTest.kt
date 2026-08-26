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
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.VectorSource
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

/** Records a layer into the desired state the way the applier does. */
private fun StyleNode.insertLayer(node: LayerNode<*>, index: Int) {
  children.add(index, node)
  onChildInserted(index, node)
}

/** Removes a layer from the desired state the way the applier does. */
private fun StyleNode.removeLayerAt(node: LayerNode<*>, index: Int) {
  children.removeAt(index)
  onChildRemoved(index, node)
}

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
  fun shoudGetBaseSource() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      assertEquals(testSources[1], s.sourceManager.getBaseSource("bar"))
      assertEquals(null, s.sourceManager.getBaseSource("BAR"))
    }
  }

  @Test
  fun shouldAddUserSource() = runComposeUiTest {
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
  fun shouldRemoveUserSource() = runComposeUiTest {
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
  fun unchangedSourceStatePreservesTheSnapshot() = runComposeUiTest {
    runOnUiThread {
      val source = vectorSource("source", "same")
      val style = RecordingStyleBinding(baseSources = listOf(source))
      val state = StyleState()
      state.attach(StyleNode(style, null))
      val previousSources = state.sources

      style.replaceSource(vectorSource("source", "same"))
      state.refreshSource("source")

      assertSame(previousSources, state.sources)
      assertSame(source, state.sources["source"])
    }
  }

  @Test
  fun changedSourceStateReplacesOnlyTheAffectedSource() = runComposeUiTest {
    runOnUiThread {
      val source = vectorSource("source", "attribution")
      val stable = vectorSource("stable", "stable attribution")
      val style = RecordingStyleBinding(baseSources = listOf(source, stable))
      val state = StyleState()
      state.attach(StyleNode(style, null))
      val previousSources = state.sources
      val replacement =
        RasterSource(
          id = "source",
          tiles = listOf("https://example.com/{z}/{x}/{y}.png"),
          options = TileSetOptions(attributionHtml = "changed attribution"),
        )

      style.replaceSource(replacement)
      state.refreshSource("source")

      assertNotSame(previousSources, state.sources)
      assertSame(replacement, state.sources["source"])
      assertSame(stable, state.sources["stable"])
    }
  }

  @Test
  fun missingSourceIsRemovedFromState() = runComposeUiTest {
    runOnUiThread {
      val source = vectorSource("source", "attribution")
      val style = RecordingStyleBinding(baseSources = listOf(source))
      val state = StyleState()
      state.attach(StyleNode(style, null))

      style.removeSource(source)
      state.refreshSource("source")

      assertNull(state.sources["source"])
    }
  }

  @Test
  fun unloadedStyleIgnoresSourceCallbacks() = runComposeUiTest {
    runOnUiThread {
      val source = vectorSource("source", "attribution")
      val style = RecordingStyleBinding(baseSources = listOf(source))
      val state = StyleState()
      state.attach(StyleNode(style, null))
      val previousSources = state.sources

      style.unload()
      style.removeSource(source)
      state.refreshSource("source")
      state.refreshSources()

      assertSame(previousSources, state.sources)
      assertSame(source, state.sources["source"])
    }
  }

  @Test
  fun shouldNotReplaceBaseSource() = runComposeUiTest {
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
  fun shouldNotRemoveBaseSource() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      assertFails { s.sourceManager.removeReference(testSources[1]) }
    }
  }

  @Test
  fun shouldAnchorTop() = runComposeUiTest {
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
  fun shouldAnchorBottom() = runComposeUiTest {
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
  fun shouldAnchorAbove() = runComposeUiTest {
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
  fun shouldAnchorBelow() = runComposeUiTest {
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
  fun shouldAnchorReplace() = runComposeUiTest {
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
  fun shouldRestoreAfterReplace() = runComposeUiTest {
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

      nodes.forEach { node -> s.removeLayerAt(node, 0) }
      s.applyChanges()

      assertEquals(listOf("foo", "bar", "baz"), s.binding.getLayers().map(Layer::id))
    }
  }

  @Test
  fun shouldAllowReplacementRecreationAfterUnload() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val oldNode = LayerNode(LineLayerDescriptor("old", testSources[0]), Anchor.Replace("bar"))
      val newNode = LayerNode(LineLayerDescriptor("new", testSources[0]), Anchor.Replace("bar"))

      s.insertLayer(oldNode, 0)
      s.applyChanges()
      (s.binding as RecordingStyleBinding).unload()

      s.insertLayer(newNode, 0)
      s.removeLayerAt(oldNode, 1)
      s.applyChanges()
      s.removeLayerAt(newNode, 0)
    }
  }

  @Test
  fun shouldAllowAddLayerBeforeRemove() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val l1 = LayerNode(LineLayerDescriptor("new", testSources[0]), Anchor.Top)
      val l2 = LayerNode(LineLayerDescriptor("new", testSources[1]), Anchor.Top)

      s.insertLayer(l1, 0)
      s.applyChanges()

      assertEquals(l1.layer, s.binding.getLayer("new"))

      s.insertLayer(l2, 0)
      s.removeLayerAt(l1, 1)
      s.applyChanges()

      assertEquals(l2.layer, s.binding.getLayer("new"))
    }
  }

  @Test
  fun shouldMergeAnchors() = runComposeUiTest {
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
