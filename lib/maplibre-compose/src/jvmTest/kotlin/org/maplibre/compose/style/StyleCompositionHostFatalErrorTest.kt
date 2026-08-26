package org.maplibre.compose.style

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.TileSetOptions

@OptIn(ExperimentalCoroutinesApi::class)
class StyleCompositionHostFatalErrorTest {

  @Test
  fun a_fatal_error_is_not_swallowed_as_a_content_error() = runTest {
    val recording =
      object : OpRecordingStyleBinding() {
        override fun addSource(source: Source) = throw OutOfMemoryError("simulated")
      }
    val rootNode = StyleNode(recording, null)
    val host =
      StyleCompositionHost(
        rootNode = rootNode,
        dispatcher = StandardTestDispatcher(testScheduler),
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        logger = null,
      )

    host.setContent {
      RasterLayer(
        id = "raster",
        source =
          RasterSource(
            "tiles",
            listOf("https://example.invalid/{z}/{x}/{y}.png"),
            TileSetOptions(),
          ),
      )
    }
    testScheduler.advanceUntilIdle()

    // The error propagated to the coroutine machinery instead of being recorded as ordinary.
    assertNull(host.contentError)

    host.close()
    testScheduler.advanceUntilIdle()
  }
}
