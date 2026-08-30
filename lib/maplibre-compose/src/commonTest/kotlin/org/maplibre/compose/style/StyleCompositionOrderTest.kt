package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.BackgroundLayer
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

  @Test
  fun a_second_apply_does_not_move_already_placed_layers() = runTest {
    val anchors =
      listOf(
        Anchor.Top,
        Anchor.Bottom,
        Anchor.Above("water"),
        Anchor.Below("roads"),
        Anchor.Replace("park"),
      )
    for (anchor in anchors) {
      val source =
        RasterSource("composed-source", listOf("https://example.invalid/{z}/{x}/{y}.png"))
      val first = RasterLayer("first-layer", source)
      val second = RasterLayer("second-layer", source)
      val revision =
        DesiredStyleRevision(
          sources = listOf(source.definition()),
          layers =
            listOf(
              DesiredStyleLayer(first.definition(), anchor, null, null),
              DesiredStyleLayer(second.definition(), anchor, null, null),
            ),
          images = emptyList(),
        )
      val style =
        RecordingStyleBinding(
          layers =
            listOf(BackgroundLayer("water"), BackgroundLayer("park"), BackgroundLayer("roads"))
        )
      val reconciler = StyleReconciler()

      reconciler.apply(style, revision)
      val afterFirst = style.layerIds()
      reconciler.apply(style, revision)

      assertEquals(afterFirst, style.layerIds(), "anchor $anchor")
    }
  }

  @Test
  fun a_single_above_layer_does_not_move_onto_itself() = runTest {
    val source = RasterSource("composed-source", listOf("https://example.invalid/{z}/{x}/{y}.png"))
    val layer = RasterLayer("hillshade", source)
    val revision =
      DesiredStyleRevision(
        sources = listOf(source.definition()),
        layers = listOf(DesiredStyleLayer(layer.definition(), Anchor.Above("water"), null, null)),
        images = emptyList(),
      )
    val style = RecordingStyleBinding(layers = listOf(BackgroundLayer("water")))
    val reconciler = StyleReconciler()

    reconciler.apply(style, revision)
    reconciler.apply(style, revision)

    assertEquals(listOf("water", "hillshade"), style.layerIds())
  }
}
