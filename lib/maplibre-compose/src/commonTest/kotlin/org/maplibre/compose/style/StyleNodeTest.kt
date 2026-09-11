package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.HillshadeLayer
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.map.FakeImageBitmap
import org.maplibre.compose.sources.RasterDemTileSource
import org.maplibre.compose.sources.RasterTileSource

class StyleNodeTest {

  @Test
  fun duplicate_resource_ids_fail_a_complete_revision() {
    val source = source("duplicate").definition()

    val error =
      assertFailsWith<IllegalArgumentException> {
        DesiredStyleRevision(
          sources = listOf(source, source),
          layers = emptyList(),
          images = emptyList(),
        )
      }

    assertTrue(error.message.orEmpty().contains("Source ID 'duplicate'"))
  }

  @Test
  fun a_revision_defensively_snapshots_its_resource_lists() {
    val sources = mutableListOf(source("first").definition())
    val layers = mutableListOf<DesiredStyleLayer>()
    val images = mutableListOf<StyleImageDefinition>()
    val revision = DesiredStyleRevision(sources, layers, images)

    sources += source("later").definition()
    layers += DesiredStyleLayer(BackgroundLayer("later").definition(), Anchor.Top, null, null)
    images +=
      StyleImageDefinition("later", ImageSnapshot.capture(FakeImageBitmap(1, 1)), false, null)

    assertEquals(listOf("first"), revision.sources.map { it.id })
    assertTrue(revision.layers.isEmpty())
    assertTrue(revision.images.isEmpty())
  }

  @Test
  fun reconciliation_applies_sources_before_layers_and_retains_unchanged_resources() = runTest {
    val style = RecordingStyleBinding()
    val recording = RecordingOperations(style)
    val reconciler = StyleReconciler()
    val source = source("tiles")
    val layer = RasterLayer("raster", source)
    val revision = revision(source, layer)

    reconciler.apply(recording, revision)
    assertEquals(listOf("source:tiles", "layer:raster"), recording.additions)

    recording.additions.clear()
    reconciler.apply(recording, revision)
    assertTrue(recording.additions.isEmpty())
    assertEquals(listOf("raster"), style.layerIds())
  }

  @Test
  fun a_second_raster_dem_revision_does_not_replace_the_source() = runTest {
    val style = RecordingStyleBinding()
    val recording = RecordingOperations(style)
    val reconciler = StyleReconciler()
    val source = RasterDemTileSource("dem", listOf("https://example.invalid/{z}/{x}/{y}.png"))
    val layer = HillshadeLayer("hillshade", source)
    val revision =
      DesiredStyleRevision(
        sources = listOf(source.definition()),
        layers = listOf(DesiredStyleLayer(layer.definition(), Anchor.Top, null, null)),
        images = emptyList(),
      )

    reconciler.apply(recording, revision)
    recording.additions.clear()
    reconciler.apply(recording, revision)

    assertTrue(recording.additions.isEmpty())
    assertEquals(listOf("hillshade"), style.layerIds())
  }

  @Test
  fun a_later_complete_revision_supersedes_a_failed_revision() = runTest {
    var fail = true
    val delegate = RecordingStyleBinding()
    val style =
      object : StyleBinding by delegate {
        override fun addSource(definition: SourceDefinition): Boolean {
          if (fail) error("engine refused")
          return delegate.addSource(definition)
        }
      }
    val reconciler = StyleReconciler()
    val first = source("first")

    assertFailsWith<IllegalStateException> {
      reconciler.apply(style, revision(first, RasterLayer("first-layer", first)))
    }

    fail = false
    val second = source("second")
    reconciler.apply(style, revision(second, RasterLayer("second-layer", second)))

    assertEquals(setOf("second"), delegate.installedSourceIds)
    assertEquals(setOf("second-layer"), delegate.installedLayerIds)
  }

  private fun source(id: String) =
    RasterTileSource(id, listOf("https://example.invalid/{z}/{x}/{y}.png"))

  private fun revision(source: RasterTileSource, layer: RasterLayer) =
    DesiredStyleRevision(
      sources = listOf(source.definition()),
      layers = listOf(DesiredStyleLayer(layer.definition(), Anchor.Top, null, null)),
      images = emptyList(),
    )

  @Test
  fun font_faces_are_set_once_per_changed_set_and_cleared_when_released() = runTest {
    val style = RecordingStyleBinding()
    val reconciler = StyleReconciler()
    val body = StyleFontDefinition("Body", FontFile(byteArrayOf(1)))
    val title = StyleFontDefinition("Title", FontFile(byteArrayOf(2)))

    reconciler.apply(style, DesiredStyleRevision(emptyList(), emptyList(), emptyList()))
    assertTrue(style.fontFaceCalls.isEmpty())

    val withFonts = { fonts: List<StyleFontDefinition> ->
      DesiredStyleRevision(emptyList(), emptyList(), emptyList(), fonts = fonts)
    }
    reconciler.apply(style, withFonts(listOf(body, title)))
    reconciler.apply(style, withFonts(listOf(body, title)))
    assertEquals(listOf(listOf(body, title)), style.fontFaceCalls)

    reconciler.apply(style, withFonts(listOf(body)))
    assertEquals(listOf(body), style.fonts)

    reconciler.apply(style, withFonts(emptyList()))
    assertEquals(3, style.fontFaceCalls.size)
    assertTrue(style.fonts.isEmpty())
  }

  @Test
  fun a_reloaded_style_receives_the_fonts_again() = runTest {
    val reconciler = StyleReconciler()
    val fonts = listOf(StyleFontDefinition("Body", FontFile(byteArrayOf(1))))
    val revision = DesiredStyleRevision(emptyList(), emptyList(), emptyList(), fonts = fonts)

    val first = RecordingStyleBinding()
    reconciler.apply(first, revision)
    val second = RecordingStyleBinding()
    reconciler.apply(second, revision)

    assertEquals(fonts, second.fonts)
  }

  private class RecordingOperations(private val delegate: RecordingStyleBinding) :
    StyleBinding by delegate {
    val additions = mutableListOf<String>()

    override fun addSource(definition: SourceDefinition): Boolean =
      delegate.addSource(definition).also { additions += "source:${definition.id}" }

    override fun addLayer(definition: LayerDefinition, beforeLayerId: String): Boolean =
      delegate.addLayer(definition, beforeLayerId).also {
        additions += "layer:${definition.id}"
      }
  }
}
