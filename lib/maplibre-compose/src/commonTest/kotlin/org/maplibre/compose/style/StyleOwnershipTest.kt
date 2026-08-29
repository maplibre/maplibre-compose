package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.RasterSource

class StyleOwnershipTest {

  @Test
  fun two_maps_reconcile_one_revision_independently() = runTest {
    val source = RasterSource("shared-source", listOf("https://example.invalid/{z}/{x}/{y}.png"))
    val layer = RasterLayer("shared-layer", source)
    val revision =
      DesiredStyleRevision(
        sources = listOf(source.definition()),
        layers = listOf(DesiredStyleLayer(layer.definition(), Anchor.Top, null, null)),
        images = emptyList(),
      )
    val first = RecordingStyleBinding()
    val second = RecordingStyleBinding()
    val firstReconciler = StyleReconciler()
    val secondReconciler = StyleReconciler()

    firstReconciler.apply(first, revision)
    secondReconciler.apply(second, revision)
    firstReconciler.apply(first, DesiredStyleRevision.Empty)

    assertEquals(emptySet(), first.installedSourceIds)
    assertEquals(emptySet(), first.installedLayerIds)
    assertEquals(setOf("shared-source"), second.installedSourceIds)
    assertEquals(setOf("shared-layer"), second.installedLayerIds)
  }
}
