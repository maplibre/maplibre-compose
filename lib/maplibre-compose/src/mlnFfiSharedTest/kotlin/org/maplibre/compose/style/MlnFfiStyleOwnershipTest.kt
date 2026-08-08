package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class MlnFfiStyleOwnershipTest {

  @Test
  fun a_source_attached_to_one_live_style_cannot_mutate_another() {
    BridgeMapFixture.create().use { first ->
      BridgeMapFixture.create().use { second ->
        first.loadStyle(BaseStyle.Empty)
        second.loadStyle(BaseStyle.Empty)
        val firstStyle = assertNotNull(first.style)
        val secondStyle = assertNotNull(second.style)
        val source = emptySource("shared-source")
        firstStyle.addSource(source)

        assertFailsWith<IllegalStateException> { secondStyle.addSource(source) }
        assertFailsWith<IllegalArgumentException> { secondStyle.removeSource(source) }

        assertNotNull(firstStyle.getSource(source.id), "the owning style should keep its source")
        assertNull(secondStyle.getSource(source.id), "the foreign style should remain unchanged")
      }
    }
  }

  @Test
  fun a_layer_attached_to_one_live_style_cannot_mutate_another() {
    BridgeMapFixture.create().use { first ->
      BridgeMapFixture.create().use { second ->
        first.loadStyle(BaseStyle.Empty)
        second.loadStyle(BaseStyle.Empty)
        val firstStyle = assertNotNull(first.style)
        val secondStyle = assertNotNull(second.style)
        val layer = BackgroundLayer("shared-layer")
        firstStyle.addLayer(layer)

        assertFailsWith<IllegalStateException> { secondStyle.addLayer(layer) }
        assertFailsWith<IllegalArgumentException> { secondStyle.removeLayer(layer) }

        assertNotNull(firstStyle.getLayer(layer.id), "the owning style should keep its layer")
        assertNull(secondStyle.getLayer(layer.id), "the foreign style should remain unchanged")
      }
    }
  }

  @Test
  fun descriptors_can_be_reused_after_their_style_unloads() {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val source = emptySource("reusable-source")
      val layer = BackgroundLayer("reusable-layer")
      assertNotNull(fixture.style).also { style ->
        style.addSource(source)
        style.addLayer(layer)
      }

      fixture.events.clear()
      fixture.loadStyle(REPLACEMENT_STYLE)
      assertNotNull(fixture.style).also { replacement ->
        replacement.addSource(source)
        replacement.addLayer(layer)
        assertNotNull(replacement.getSource(source.id))
        assertNotNull(replacement.getLayer(layer.id))
      }
    }
  }

  private companion object {
    fun emptySource(id: String) =
      GeoJsonSource(id, GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())

    val REPLACEMENT_STYLE =
      BaseStyle.Json("""{"version":8,"name":"replacement","sources":{},"layers":[]}""")
  }
}
