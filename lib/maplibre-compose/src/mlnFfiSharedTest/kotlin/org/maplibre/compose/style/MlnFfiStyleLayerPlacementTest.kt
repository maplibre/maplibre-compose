package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.mlnffi.BridgeMapFixture

class MlnFfiStyleLayerPlacementTest {

  @Test
  fun adding_above_a_missing_layer_is_rejected_without_adding_on_top() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(STYLE)
      val style = requireNotNull(fixture.style)
      val layer = BackgroundLayer("new")
      val initialOrder = fixture.session.currentStyleLayerIds()

      assertFailsWith<IllegalArgumentException> { style.addLayerAbove("missing", layer) }

      assertNull(style.getLayer(layer.id))
      assertEquals(initialOrder, fixture.session.currentStyleLayerIds())
    }
  }

  @Test
  fun adding_above_the_topmost_layer_still_adds_on_top() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(STYLE)
      val initialOrder = fixture.session.currentStyleLayerIds()

      requireNotNull(fixture.style).addLayerAbove(initialOrder.last(), BackgroundLayer("new"))

      assertEquals(initialOrder + "new", fixture.session.currentStyleLayerIds())
    }
  }

  @Test
  fun adding_at_an_invalid_index_is_rejected() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(STYLE)
      val style = requireNotNull(fixture.style)
      val initialOrder = fixture.session.currentStyleLayerIds()

      assertFailsWith<IllegalArgumentException> {
        style.addLayerAt(-1, BackgroundLayer("negative"))
      }
      assertFailsWith<IllegalArgumentException> {
        style.addLayerAt(initialOrder.size + 1, BackgroundLayer("past-end"))
      }

      assertEquals(initialOrder, fixture.session.currentStyleLayerIds())
    }
  }

  private companion object {
    val STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"bottom","type":"background"},{"id":"top","type":"background"}]}"""
      )
  }
}
