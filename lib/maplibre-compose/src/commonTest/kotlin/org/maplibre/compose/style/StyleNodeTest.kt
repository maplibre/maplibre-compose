package org.maplibre.compose.style

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
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
      s.onEndChanges()
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
      s.onEndChanges()
      s.sourceManager.removeReference(newSource)
      assertEquals(3, s.style.getSources().size)
      assertNull(s.style.getSource("new"))
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
      s.onEndChanges()
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
      s.onEndChanges()
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
      s.onEndChanges()
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
      s.onEndChanges()
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
      s.onEndChanges()
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
      s.onEndChanges()

      assertEquals(listOf("foo", "new0", "new1", "new2", "baz"), s.style.getLayers().map(Layer::id))

      nodes.forEach { node -> s.layerManager.removeLayer(node, 0) }
      s.onEndChanges()

      assertEquals(listOf("foo", "bar", "baz"), s.style.getLayers().map(Layer::id))
    }
  }

  @Test
  fun shouldAllowAddLayerBeforeRemove() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val l1 = LayerNode(LineLayer("new", testSources[0]), Anchor.Top)
      val l2 = LayerNode(LineLayer("new", testSources[1]), Anchor.Top)

      s.layerManager.addLayer(l1, 0)
      s.onEndChanges()

      assertEquals(l1.layer, s.style.getLayer("new"))

      s.layerManager.addLayer(l2, 0)
      s.layerManager.removeLayer(l1, 1)
      s.onEndChanges()

      assertEquals(l2.layer, s.style.getLayer("new"))
    }
  }

  @Test
  fun shouldMergeAnchors() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()

      s.layerManager.addLayer(LayerNode(LineLayer("b1", testSources[0]), Anchor.Bottom), 0)
      s.layerManager.addLayer(LayerNode(LineLayer("t1", testSources[0]), Anchor.Top), 0)
      s.onEndChanges()

      assertEquals(listOf("b1", "foo", "bar", "baz", "t1"), s.style.getLayers().map(Layer::id))

      s.layerManager.addLayer(LayerNode(LineLayer("b2", testSources[0]), Anchor.Bottom), 0)
      s.layerManager.addLayer(LayerNode(LineLayer("t2", testSources[0]), Anchor.Top), 0)
      s.onEndChanges()

      assertEquals(
        listOf("b2", "b1", "foo", "bar", "baz", "t2", "t1"),
        s.style.getLayers().map(Layer::id),
      )
    }
  }
}
