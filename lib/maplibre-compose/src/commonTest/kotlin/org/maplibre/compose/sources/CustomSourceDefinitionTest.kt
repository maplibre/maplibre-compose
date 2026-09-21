package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.SourceInstallation

class CustomSourceDefinitionTest {
  @Test
  fun an_installed_source_uses_the_replacement_provider() = runTest {
    val first = VectorTileProvider { byteArrayOf(1) }
    val second = VectorTileProvider { byteArrayOf(2) }
    val source = CustomVectorTileSource("custom", provider = first)
    val binding = RecordingStyleBinding()
    val handle = SourceInstallation(binding, source.definition())
    val installedProvider = requireNotNull(binding.customVectorProvider)

    handle.update(CustomVectorTileSource("custom", provider = second).definition())

    assertContentEquals(byteArrayOf(2), installedProvider.loadTile(TileCoordinate(0, 0, 0)))
  }
}
