package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import co.touchlab.kermit.Logger
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.desktop.DesktopRuntimeOptions
import org.maplibre.compose.desktop.HeadlessVulkanMapHostFactory
import org.maplibre.compose.desktop.LocalDesktopMapHostFactory
import org.maplibre.compose.desktop.LocalDesktopRuntimeOptions
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.offline.rememberOfflineManager
import org.maplibre.compose.offline.rememberOfflinePacksSource
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/**
 * Composes real maps against a real GPU, with no window.
 *
 * This is the layer the headless manager tests cannot reach. `DesktopOfflineManagerTest` proves the
 * offline manager starts and lists packs, but the demo app crashed on *opening* the offline screen
 * — which means the fault is in the composition around the manager, not in the manager. Only a test
 * that actually composes the same content can catch that, so this one does.
 *
 * Every test skips when Vulkan is unavailable, because a runner without a GPU or a software driver
 * says nothing about the code under test.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopMapCompositionTest {

  private val cacheDirectory = Files.createTempDirectory("maplibre-composition-test")

  private val runtimeOptions =
    DesktopRuntimeOptions(
      cachePath = cacheDirectory.resolve("cache.db"),
      maximumCacheSizeBytes = null,
    )

  @AfterTest
  fun cleanUp() {
    cacheDirectory.toFile().deleteRecursively()
  }

  @Test
  fun `an empty style composes without error`() = runHeadlessMapTest { errors ->
    MaplibreMap(
      modifier = Modifier,
      baseStyle = BaseStyle.Empty,
      logger = Logger.withTag("composition-test"),
      onMapLoadFailed = { errors += "mapLoadFailed: $it" },
    )
  }

  /**
   * The exact shape the offline demo composes.
   *
   * A pack source with no packs is the state the screen opens in, so the empty case is the one that
   * matters: an empty `FeatureCollection` still has to produce valid source JSON, and the `switch`
   * still has to compile even though no feature will ever be matched against it.
   */
  @Test
  fun `the offline demo layer composes without error`() = runHeadlessMapTest { errors ->
    MaplibreMap(
      modifier = Modifier,
      baseStyle = BaseStyle.Empty,
      logger = Logger.withTag("composition-test"),
      onMapLoadFailed = { errors += "mapLoadFailed: $it" },
    ) {
      val offlineManager = rememberOfflineManager()
      FillLayer(
        id = "offline-packs",
        source = rememberOfflinePacksSource(offlineManager.packs),
        opacity = const(0.5f),
        color =
          switch(
            feature["status"].asString(),
            case(label = "Complete", output = const(Color.Green)),
            case(label = "Downloading", output = const(Color.Blue)),
            case(label = "Paused", output = const(Color.Yellow)),
            fallback = const(Color.Red),
          ),
      )
    }
  }

  /** An empty GeoJSON source on its own, to separate a source-JSON fault from a layer fault. */
  @Test
  fun `an empty geojson source composes without error`() = runHeadlessMapTest { errors ->
    MaplibreMap(
      modifier = Modifier,
      baseStyle = BaseStyle.Empty,
      logger = Logger.withTag("composition-test"),
      onMapLoadFailed = { errors += "mapLoadFailed: $it" },
    ) {
      FillLayer(
        id = "empty",
        source =
          rememberGeoJsonSource(
            data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>())
          ),
        color = const(Color.Red),
      )
    }
  }

  /**
   * Composes [content] on a headless map and fails if anything reported an error.
   *
   * Uncaught exceptions in composition already fail the test on their own; the collected errors
   * cover the ones MapLibre reports asynchronously instead of throwing.
   */
  private fun runHeadlessMapTest(content: @Composable (MutableList<String>) -> Unit) =
    runComposeUiTest {
      val factory = HeadlessVulkanMapHostFactory.createOrNull()
      if (factory == null) {
        System.err.println("Skipping: no usable Vulkan implementation")
        return@runComposeUiTest
      }
      val errors = mutableListOf<String>()
      setContent {
        CompositionLocalProvider(
          LocalDesktopMapHostFactory provides factory,
          LocalDesktopRuntimeOptions provides runtimeOptions,
        ) {
          content(errors)
        }
      }
      waitForIdle()
      assertTrue(errors.isEmpty(), "The composition reported errors: $errors")
      // Without this the test would pass by doing nothing: a surface that never lays out never
      // acquires a frame, and a map that never gets a frame never creates a runtime or a style.
      assertTrue(
        factory.created.any { it.acquiredFrames > 0 },
        "No frame reached MapLibre; the map never rendered.",
      )
    }
}
