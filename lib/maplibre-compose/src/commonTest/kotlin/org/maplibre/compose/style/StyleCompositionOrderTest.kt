package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.RasterSource

class StyleCompositionOrderTest {

  @Test
  fun a_complete_revision_preserves_explicit_layer_order() = runTest {
    val source = RasterSource("composed-source", listOf("https://example.invalid/{z}/{x}/{y}.png"))
    val first = RasterLayer("first-layer", source)
    val second = RasterLayer("second-layer", source)
    val revision =
      DesiredStyleRevision(
        sources = listOf(source.definition()),
        layers =
          listOf(
            DesiredStyleLayer(first.definition(), Anchor.Top, null, null),
            DesiredStyleLayer(second.definition(), Anchor.Top, null, null),
          ),
        images = emptyList(),
      )
    val style = RecordingStyleBinding()

    StyleReconciler().apply(style, revision)

    assertEquals(listOf("first-layer", "second-layer"), revision.layers.map { it.definition.id })
    assertEquals(listOf("first-layer", "second-layer"), style.layerIds())
  }
}
