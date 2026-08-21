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
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.VectorSource
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

@OptIn(ExperimentalTestApi::class)
abstract class StyleNodeTest {
  private val testSources by lazy {
    listOf(
      VectorSource("foo", "https://example.com/{z}/{x}/{y}.pbf"),
      GeoJsonSource("bar", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions()),
      GeoJsonSource("baz", GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions()),
    )
  }

  private val testLayers by lazy {
    listOf(
      LineLayer("foo", testSources[0]),
      LineLayer("bar", testSources[1]),
      LineLayer("baz", testSources[2]),
    )
  }

  private fun makeStyleNode(): StyleNode {
    return StyleNode(SafeStyle(FakeStyle(emptyList(), testSources, testLayers)), null)
  }

  private fun vectorSource(id: String, attribution: String): VectorSource =
    VectorSource(
      id = id,
      tiles = listOf("https://example.com/{z}/{x}/{y}.pbf"),
      options = TileSetOptions(attributionHtml = attribution),
    )

  @BeforeTest open fun platformSetup() {}

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
      assertEquals(4, s.style.getSources().size)
      assertEquals(newSource, s.style.getSource("new"))
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
      assertEquals(3, s.style.getSources().size)
      assertNull(s.style.getSource("new"))
    }
  }

  @Test
  fun unchangedSourceStatePreservesTheSnapshot() = runComposeUiTest {
    runOnUiThread {
      val source = vectorSource("source", "same")
      val style = FakeStyle(emptyList(), listOf(source), emptyList())
      val state = StyleState()
      state.attach(StyleNode(SafeStyle(style), null))
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
      val style = FakeStyle(emptyList(), listOf(source, stable), emptyList())
      val state = StyleState()
      state.attach(StyleNode(SafeStyle(style), null))
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
      val style = FakeStyle(emptyList(), listOf(source), emptyList())
      val state = StyleState()
      state.attach(StyleNode(SafeStyle(style), null))

      style.removeSource(source)
      state.refreshSource("source")

      assertNull(state.sources["source"])
    }
  }

  @Test
  fun unloadedStyleIgnoresSourceCallbacks() = runComposeUiTest {
    runOnUiThread {
      val source = vectorSource("source", "attribution")
      val style = FakeStyle(emptyList(), listOf(source), emptyList())
      val safeStyle = SafeStyle(style)
      val state = StyleState()
      state.attach(StyleNode(safeStyle, null))
      val previousSources = state.sources

      safeStyle.unload()
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
      val nodes = (0..2).map { LayerNode(LineLayer("new$it", testSources[0]), Anchor.Top) }
      nodes.forEachIndexed { i, node -> s.layerManager.addLayer(node, i) }
      s.applyChanges()
      assertEquals(
        listOf("foo", "bar", "baz", "new0", "new1", "new2"),
        s.style.getLayers().map(Layer::id),
      )
    }
  }

  @Test
  fun shouldAnchorBottom() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val nodes = (0..2).map { LayerNode(LineLayer("new$it", testSources[0]), Anchor.Bottom) }
      nodes.forEachIndexed { i, node -> s.layerManager.addLayer(node, i) }
      s.applyChanges()
      assertEquals(
        listOf("new0", "new1", "new2", "foo", "bar", "baz"),
        s.style.getLayers().map(Layer::id),
      )
    }
  }

  @Test
  fun shouldAnchorAbove() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val nodes = (0..2).map { LayerNode(LineLayer("new$it", testSources[0]), Anchor.Above("foo")) }
      nodes.forEachIndexed { i, node -> s.layerManager.addLayer(node, i) }
      s.applyChanges()
      assertEquals(
        listOf("foo", "new0", "new1", "new2", "bar", "baz"),
        s.style.getLayers().map(Layer::id),
      )
    }
  }

  @Test
  fun shouldAnchorBelow() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val nodes = (0..2).map { LayerNode(LineLayer("new$it", testSources[0]), Anchor.Below("baz")) }
      nodes.forEachIndexed { i, node -> s.layerManager.addLayer(node, i) }
      s.applyChanges()
      assertEquals(
        listOf("foo", "bar", "new0", "new1", "new2", "baz"),
        s.style.getLayers().map(Layer::id),
      )
    }
  }

  @Test
  fun shouldAnchorReplace() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val nodes =
        (0..2).map { LayerNode(LineLayer("new$it", testSources[0]), Anchor.Replace("bar")) }
      nodes.forEachIndexed { i, node -> s.layerManager.addLayer(node, i) }
      s.applyChanges()
      assertEquals(listOf("foo", "new0", "new1", "new2", "baz"), s.style.getLayers().map(Layer::id))
    }
  }

  @Test
  fun shouldRestoreAfterReplace() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val nodes =
        (0..2).map { LayerNode(LineLayer("new$it", testSources[0]), Anchor.Replace("bar")) }

      nodes.forEachIndexed { i, node -> s.layerManager.addLayer(node, i) }
      s.applyChanges()

      assertEquals(listOf("foo", "new0", "new1", "new2", "baz"), s.style.getLayers().map(Layer::id))

      nodes.forEach { node -> s.layerManager.removeLayer(node, 0) }
      s.applyChanges()

      assertEquals(listOf("foo", "bar", "baz"), s.style.getLayers().map(Layer::id))
    }
  }

  @Test
  fun shouldAllowReplacementRecreationAfterUnload() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val oldNode = LayerNode(LineLayer("old", testSources[0]), Anchor.Replace("bar"))
      val newNode = LayerNode(LineLayer("new", testSources[0]), Anchor.Replace("bar"))

      s.layerManager.addLayer(oldNode, 0)
      s.applyChanges()
      s.style.unload()

      s.layerManager.addLayer(newNode, 0)
      s.layerManager.removeLayer(oldNode, 1)
      s.applyChanges()
      s.layerManager.removeLayer(newNode, 0)
    }
  }

  @Test
  fun shouldAllowAddLayerBeforeRemove() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val l1 = LayerNode(LineLayer("new", testSources[0]), Anchor.Top)
      val l2 = LayerNode(LineLayer("new", testSources[1]), Anchor.Top)

      s.layerManager.addLayer(l1, 0)
      s.applyChanges()

      assertEquals(l1.layer, s.style.getLayer("new"))

      s.layerManager.addLayer(l2, 0)
      s.layerManager.removeLayer(l1, 1)
      s.applyChanges()

      assertEquals(l2.layer, s.style.getLayer("new"))
    }
  }

  @Test
  fun shouldMergeAnchors() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()

      s.layerManager.addLayer(LayerNode(LineLayer("b1", testSources[0]), Anchor.Bottom), 0)
      s.layerManager.addLayer(LayerNode(LineLayer("t1", testSources[0]), Anchor.Top), 0)
      s.applyChanges()

      assertEquals(listOf("b1", "foo", "bar", "baz", "t1"), s.style.getLayers().map(Layer::id))

      s.layerManager.addLayer(LayerNode(LineLayer("b2", testSources[0]), Anchor.Bottom), 0)
      s.layerManager.addLayer(LayerNode(LineLayer("t2", testSources[0]), Anchor.Top), 0)
      s.applyChanges()

      assertEquals(
        listOf("b2", "b1", "foo", "bar", "baz", "t2", "t1"),
        s.style.getLayers().map(Layer::id),
      )
    }
  }
}
