package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.style.SourceHandle

class CustomSourceDefinitionTest {
  @Test
  fun replacing_a_provider_publishes_a_new_definition() = runTest {
    val first = VectorTileProvider { byteArrayOf(1) }
    val second = VectorTileProvider { byteArrayOf(2) }
    val source = CustomVectorSource("custom", provider = first)
    val firstDefinition = source.definition() as SourceDefinition.CustomVector
    val binding = RecordingStyleBinding()
    val handle = SourceHandle(binding, firstDefinition)
    val installedProvider = requireNotNull(binding.customVectorProvider)

    source.setDesiredProvider(second)
    val secondDefinition = source.definition() as SourceDefinition.CustomVector
    handle.update(secondDefinition)

    assertSame(first, firstDefinition.provider)
    assertSame(second, secondDefinition.provider)
    assertContentEquals(byteArrayOf(2), installedProvider.loadTile(TileCoordinate(0, 0, 0)))
  }
}
