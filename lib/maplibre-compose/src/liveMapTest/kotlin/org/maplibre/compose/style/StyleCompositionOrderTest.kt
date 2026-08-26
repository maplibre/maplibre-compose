package org.maplibre.compose.style

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.layers.FillLayerDescriptor
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.layers.LayerNode as ComposeLayerNode
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.SourceReferenceEffect
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

class StyleCompositionOrderTest {

  @Test
  fun a_source_reaches_the_style_before_its_layer() = runTest {
    val style = SourceBeforeLayerBinding()
    val rootNode = StyleNode(style, logger = null)
    val source =
      GeoJsonSource(
        "composed-source",
        GeoJsonData.Features(featureCollectionOf()),
        GeoJsonOptions(),
      )
    val host =
      StyleCompositionHost(
        dispatcher = StandardTestDispatcher(testScheduler),
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        logger = null,
      )
    try {
      host.setContent(rootNode) {
        SourceReferenceEffect(source)
        ComposeLayerNode(
          factory = { FillLayerDescriptor("composed-layer", source) },
          update = {},
          onClick = null,
          onLongClick = null,
        )
      }
      testScheduler.advanceUntilIdle()
      assertEquals(listOf("source:composed-source", "layer:composed-layer"), style.additions)
    } finally {
      host.close()
      testScheduler.advanceUntilIdle()
    }
  }

  private class SourceBeforeLayerBinding : RecordingStyleBinding() {
    val additions = mutableListOf<String>()

    override fun addSource(source: Source) {
      super.addSource(source)
      additions += "source:${source.id}"
    }

    override fun addLayer(layer: Layer) {
      super.addLayer(layer)
      additions += "layer:${layer.id}"
    }
  }
}
