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
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.LineLayerDescriptor
import org.maplibre.compose.map.MapState
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
private fun StyleNode.removeLayerAt(index: Int) {
  children.removeAt(index)
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

  /** A state whose style node points at [style], so [MapState.sources] snapshots it. */
  private fun mapStateOver(style: StyleBinding): MapState {
    val mapState = MapState(cameraState = CameraState(CameraPosition()))
    mapState.styleNode.binding = style
    mapState.sources.refreshSources()
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
  fun changedSourceStateReplacesOnlyTheAffectedSource() = runComposeUiTest {
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
  fun missingSourceIsRemovedFromState() = runComposeUiTest {
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
  fun unloadedStyleIgnoresSourceCallbacks() = runComposeUiTest {
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

      repeat(nodes.size) { s.removeLayerAt(0) }
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
      s.removeLayerAt(1)
      s.applyChanges()
      s.removeLayerAt(0)
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
      s.removeLayerAt(1)
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
