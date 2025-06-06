package dev.sargunv.maplibrecompose.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.sargunv.maplibrecompose.compose.engine.LayerNode
import dev.sargunv.maplibrecompose.compose.engine.StyleNode
import dev.sargunv.maplibrecompose.compose.layer.Anchor
import dev.sargunv.maplibrecompose.core.SafeStyle
import dev.sargunv.maplibrecompose.core.layer.Layer
import dev.sargunv.maplibrecompose.core.layer.LineLayer
import dev.sargunv.maplibrecompose.core.source.GeoJsonData
import dev.sargunv.maplibrecompose.core.source.GeoJsonOptions
import dev.sargunv.maplibrecompose.core.source.GeoJsonSource
import dev.sargunv.maplibrecompose.core.source.VectorSource
import io.github.dellisd.spatialk.geojson.FeatureCollection
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
abstract class StyleNodeTest {
  private val testSources by lazy {
    listOf(
      VectorSource("foo", "https://example.com/{z}/{x}/{y}.pbf"),
      GeoJsonSource("bar", GeoJsonData.Features(FeatureCollection()), GeoJsonOptions()),
      GeoJsonSource("baz", GeoJsonData.Features(FeatureCollection()), GeoJsonOptions()),
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
      assertFails { s.sourceManager.getBaseSource("BAR") }
    }
  }

  @Test
  fun shouldAddUserSource() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val newSource =
        GeoJsonSource("new", GeoJsonData.Features(FeatureCollection()), GeoJsonOptions())
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
        GeoJsonSource("new", GeoJsonData.Features(FeatureCollection()), GeoJsonOptions())
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
          GeoJsonSource("foo", GeoJsonData.Features(FeatureCollection()), GeoJsonOptions())
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
  fun shouldAllowAddSourceBeforeRemove() = runComposeUiTest {
    runOnUiThread {
      val s = makeStyleNode()
      val s1 = GeoJsonSource("new", GeoJsonData.Features(FeatureCollection()), GeoJsonOptions())
      val s2 = GeoJsonSource("new", GeoJsonData.Features(FeatureCollection()), GeoJsonOptions())

      s.sourceManager.addReference(s1)
      s.onEndChanges()

      assertEquals(s1, s.style.getSource("new"))

      s.sourceManager.addReference(s2)
      s.sourceManager.removeReference(s1)
      s.onEndChanges()

      assertEquals(s2, s.style.getSource("new"))
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
