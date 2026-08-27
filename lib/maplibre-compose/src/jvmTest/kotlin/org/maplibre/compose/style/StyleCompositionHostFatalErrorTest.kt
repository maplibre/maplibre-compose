package org.maplibre.compose.style

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.testing.RecordingLogger

@OptIn(ExperimentalCoroutinesApi::class)
class StyleCompositionHostFatalErrorTest {

  @Test
  fun a_fatal_error_is_not_swallowed_as_a_content_error() = runTest {
    val fatal = OutOfMemoryError("simulated")
    val recording =
      object : RecordingStyleBinding() {
        override fun addSource(source: Source) = throw fatal
      }
    val rootNode = StyleNode(recording, null)
    // The host's CoroutineExceptionHandler logs whatever escapes its coroutines.
    val log = RecordingLogger("fatal-error-test")
    val host =
      StyleCompositionHost(
        rootNode = rootNode,
        dispatcher = StandardTestDispatcher(testScheduler),
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        logger = log.logger,
      )
    val errors = collectStyleErrors(host)

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

    assertTrue(
      log.throwables.any { it === fatal },
      "the fatal error must escape to the host's handler: ${log.throwables}",
    )
    assertTrue(errors.isEmpty(), "a fatal error must not surface as a style error: $errors")
    assertNull(host.contentError, "a fatal error must not be recorded as a content error")

    host.close()
    testScheduler.advanceUntilIdle()
  }
}
