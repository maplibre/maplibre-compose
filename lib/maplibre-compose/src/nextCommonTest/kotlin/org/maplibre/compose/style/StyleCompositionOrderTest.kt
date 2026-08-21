package org.maplibre.compose.style

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.withRunningRecomposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.maplibre.compose.layers.FillLayer
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
    val frameClock = BroadcastFrameClock()
    withContext(frameClock) {
      withRunningRecomposer { recomposer ->
        val style = SourceBeforeLayerStyle()
        val rootNode = StyleNode(SafeStyle(style), logger = null)
        val source =
          GeoJsonSource(
            "composed-source",
            GeoJsonData.Features(featureCollectionOf()),
            GeoJsonOptions(),
          )
        val composition = Composition(MapNodeApplier(rootNode), recomposer)
        try {
          composition.setContent {
            StyleContent(rootNode) {
              SourceReferenceEffect(source)
              ComposeLayerNode(
                factory = { FillLayer("composed-layer", source) },
                update = {},
                onClick = null,
                onLongClick = null,
              )
            }
          }
          while (!frameClock.hasAwaiters) yield()
          frameClock.sendFrame(0)
          yield()
          recomposer.awaitIdle()
          assertEquals(listOf("source:composed-source", "layer:composed-layer"), style.additions)
        } finally {
          composition.dispose()
        }
      }
    }
  }

  private class SourceBeforeLayerStyle(
    private val delegate: Style = FakeStyle(emptyList(), emptyList(), emptyList())
  ) : Style by delegate {
    val additions = mutableListOf<String>()

    override fun addSource(source: Source) {
      delegate.addSource(source)
      additions += "source:${source.id}"
    }

    override fun addLayer(layer: Layer) {
      delegate.addLayer(layer)
      additions += "layer:${layer.id}"
    }
  }
}
