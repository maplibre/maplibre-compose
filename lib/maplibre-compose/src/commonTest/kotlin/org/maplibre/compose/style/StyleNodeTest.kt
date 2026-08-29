package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.RasterSource

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
    layers.clear()
    images.clear()

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
    RasterSource(id, listOf("https://example.invalid/{z}/{x}/{y}.png"))

  private fun revision(source: RasterSource, layer: RasterLayer) =
    DesiredStyleRevision(
      sources = listOf(source.definition()),
      layers = listOf(DesiredStyleLayer(layer.definition(), Anchor.Top, null, null)),
      images = emptyList(),
    )

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
