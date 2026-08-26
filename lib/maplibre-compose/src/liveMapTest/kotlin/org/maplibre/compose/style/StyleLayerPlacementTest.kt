package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest

class StyleLayerPlacementTest {

  @Test
  fun adding_above_a_missing_layer_is_rejected_without_adding_on_top(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(STYLE)
      val style = requireNotNull(it.style)
      val layer = BackgroundLayer("new")
      val initialOrder = requireNotNull(style.layerIds())

      assertFailsWith<IllegalArgumentException> { style.addLayerAbove("missing", layer) }

      assertNull(style.getLayer(layer.id))
      assertEquals(initialOrder, style.layerIds())
    }
  }

  @Test
  fun adding_above_the_topmost_layer_still_adds_on_top(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(STYLE)
      val style = requireNotNull(it.style)
      val initialOrder = requireNotNull(style.layerIds())

      style.addLayerAbove(initialOrder.last(), BackgroundLayer("new"))

      assertEquals(initialOrder + "new", style.layerIds())
    }
  }

  @Test
  fun adding_at_an_invalid_index_is_rejected(): MapTestResult = runMapTest {
    createMapFixture().use {
      it.loadStyle(STYLE)
      val style = requireNotNull(it.style)
      val initialOrder = requireNotNull(style.layerIds())

      assertFailsWith<IllegalArgumentException> {
        style.addLayerAt(-1, BackgroundLayer("negative"))
      }
      assertFailsWith<IllegalArgumentException> {
        style.addLayerAt(initialOrder.size + 1, BackgroundLayer("past-end"))
      }

      assertEquals(initialOrder, style.layerIds())
    }
  }

  private companion object {
    val STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"bottom","type":"background"},{"id":"top","type":"background"}]}"""
      )
  }
}
