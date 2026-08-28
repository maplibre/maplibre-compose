package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.maplibre.compose.layers.BackgroundLayerDescriptor
import org.maplibre.compose.layers.FillLayerDescriptor
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.testing.MapTestResult
import org.maplibre.compose.testing.createMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class StyleOwnershipTest {

  @Test
  fun a_source_attached_to_one_live_style_cannot_mutate_another(): MapTestResult = runMapTest {
    createMapFixture().use { first ->
      createMapFixture().use { second ->
        first.loadStyle(BaseStyle.Empty)
        second.loadStyle(BaseStyle.Empty)
        val firstStyle = assertNotNull(first.style)
        val secondStyle = assertNotNull(second.style)
        val source = emptySource("shared-source")
        firstStyle.addSource(source)
        secondStyle.addSource(source)

        assertNotNull(firstStyle.getSource(source.id), "the first style installs the definition")
        assertNotNull(
          secondStyle.getSource(source.id),
          "the same definition can be installed twice",
        )
      }
    }
  }

  @Test
  fun a_layer_attached_to_one_live_style_cannot_mutate_another(): MapTestResult = runMapTest {
    createMapFixture().use { first ->
      createMapFixture().use { second ->
        first.loadStyle(BaseStyle.Empty)
        second.loadStyle(BaseStyle.Empty)
        val firstStyle = assertNotNull(first.style)
        val secondStyle = assertNotNull(second.style)
        val layer = BackgroundLayerDescriptor("shared-layer")
        firstStyle.addLayer(layer)
        secondStyle.addLayer(layer)

        assertNotNull(firstStyle.getLayer(layer.id), "the first style installs the definition")
        assertNotNull(secondStyle.getLayer(layer.id), "the same definition can be installed twice")
      }
    }
  }

  @Test
  fun descriptors_can_be_reused_after_their_style_unloads(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val source = emptySource("reusable-source")
      val layer = BackgroundLayerDescriptor("reusable-layer")
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

  @Test
  fun a_layer_rejects_a_source_descriptor_owned_by_another_map(): MapTestResult = runMapTest {
    createMapFixture().use { first ->
      createMapFixture().use { second ->
        first.loadStyle(BaseStyle.Empty)
        second.loadStyle(BaseStyle.Empty)
        val source = emptySource("cross-map-source")
        requireNotNull(first.style).addSource(source)
        val layer = FillLayerDescriptor("cross-map-layer", source)

        assertFailsWith<IllegalStateException> { requireNotNull(second.style).addLayer(layer) }

        assertNull(requireNotNull(second.style).getLayer(layer.id))
        assertNull(requireNotNull(second.style).getSource(source.id))
      }
    }
  }

  @Test
  fun different_live_source_descriptors_cannot_share_an_id(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = requireNotNull(fixture.style)
      val first = emptySource("duplicate")
      val second = emptySource("duplicate")
      style.addSource(first)

      assertFailsWith<IllegalStateException> { style.addSource(second) }

      assertNotNull(style.getSource(first.id))
    }
  }

  @Test
  fun a_failed_layer_attachment_can_be_retried(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BaseStyle.Empty)
      val style = requireNotNull(fixture.style)
      val layer = BackgroundLayerDescriptor("retry-layer")

      assertFailsWith<IllegalStateException> { style.addLayerBelow("missing-anchor", layer) }
      assertNull(style.getLayer(layer.id))

      style.addLayer(layer)
      assertNotNull(style.getLayer(layer.id))
    }
  }

  @Test
  fun a_layer_can_use_a_source_read_from_the_base_style(): MapTestResult = runMapTest {
    createMapFixture().use { fixture ->
      fixture.loadStyle(BASE_SOURCE_STYLE)
      val style = requireNotNull(fixture.style)
      val source = requireNotNull(style.getSource("base-source"))

      style.addLayer(FillLayerDescriptor("over-base-source", source))

      assertNotNull(style.getLayer("over-base-source"))
      assertNotNull(style.getSource("base-source"))
    }
  }

  private companion object {
    fun emptySource(id: String) =
      GeoJsonSource(id, GeoJsonData.Features(featureCollectionOf()), GeoJsonOptions())

    val REPLACEMENT_STYLE =
      BaseStyle.Json("""{"version":8,"name":"replacement","sources":{},"layers":[]}""")

    val BASE_SOURCE_STYLE =
      BaseStyle.Json(
        """
        {
          "version": 8,
          "sources": {
            "base-source": {
              "type": "geojson",
              "data": { "type": "FeatureCollection", "features": [] }
            }
          },
          "layers": []
        }
        """
          .trimIndent()
      )
  }
}
