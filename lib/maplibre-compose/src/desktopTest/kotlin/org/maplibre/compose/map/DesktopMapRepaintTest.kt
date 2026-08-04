package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
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
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

/**
 * Proves that a style change actually redraws.
 *
 * `DesktopMapCompositionTest` shows a mutation reaches MapLibre; this shows whether the user ever
 * sees it. A mutation that leaves MapLibre publishing no render update asks for no frame, and is
 * then invisible until something unrelated draws — which is exactly the reported symptom: a
 * re-added layer appears only after the map is moved a little. `addSource`, `removeSource`, and
 * `removeImage` are the calls that notify nothing on their own, which is why the style binding
 * requests a repaint for all of them.
 *
 * Each test settles first, because a busy map hides the bug: a frame still in flight would render
 * the mutation by accident.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopMapRepaintTest {

  private val cacheDirectory = Files.createTempDirectory("maplibre-repaint-test")

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
  fun `adding a layer after the map settles redraws`() {
    var visible by mutableStateOf(false)
    runRepaintTest(mutate = { visible = true }) { ToggledLayer(visible) }
  }

  /** The other half of a toggle, and equally broken before the fix. */
  @Test
  fun `removing a layer after the map settles redraws`() {
    var visible by mutableStateOf(true)
    runRepaintTest(mutate = { visible = false }) { ToggledLayer(visible) }
  }

  /**
   * Replacing a source's data, which is what a live GeoJSON feed does on every update.
   *
   * A stalled loop here means a moving map that stops moving whenever the user does.
   */
  @Test
  fun `replacing source data after the map settles redraws`() {
    var data by mutableStateOf(pointAt(longitude = 0.0))
    runRepaintTest(mutate = { data = pointAt(longitude = 10.0) }) {
      FillLayer(
        id = "data-driven",
        source = rememberGeoJsonSource(data = GeoJsonData.Features(data)),
        color = const(Color.Red),
      )
    }
  }

  /**
   * Composes [content], settles, runs [mutate], and asserts the map redrew.
   *
   * The assertion is on frames MapLibre actually rendered into, not frames acquired: the host hands
   * out a frame whenever Compose draws, so only a completed render means the change is on screen.
   */
  private fun runRepaintTest(mutate: ComposeUiTest.() -> Unit, content: @Composable () -> Unit) =
    runComposeUiTest {
      val factory = HeadlessVulkanMapHostFactory.create()

      setContent {
        CompositionLocalProvider(
          LocalDesktopMapHostFactory provides factory,
          LocalDesktopRuntimeOptions provides runtimeOptions,
        ) {
          MaplibreMap(
            modifier = Modifier,
            baseStyle = BaseStyle.Empty,
            logger = Logger.withTag("repaint-test"),
            content = content,
          )
        }
      }

      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { factory.created.isNotEmpty() }
      val host = factory.created.single()
      // Settled means "the map has drawn and then stopped drawing", which is a wait rather than a
      // fixed number of idle rounds: the map advances on a thread of its own, so how long it takes
      // to get there is a property of the machine.
      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { host.renderedFrames > 0 }
      var before = host.renderedFrames
      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
        val settled = host.renderedFrames == before
        before = host.renderedFrames
        settled
      }

      mutate()

      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { host.renderedFrames > before }
      assertTrue(
        host.renderedFrames > before,
        "The change produced no new rendered frame ($before before, ${host.renderedFrames} " +
          "after), so it would not appear until something else woke the render loop.",
      )
    }

  @Composable
  private fun ToggledLayer(visible: Boolean) {
    if (visible) {
      FillLayer(
        id = "toggled",
        source =
          rememberGeoJsonSource(
            data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>())
          ),
        color = const(Color.Red),
      )
    }
  }

  private fun pointAt(longitude: Double): FeatureCollection<Geometry, JsonObject?> =
    buildFeatureCollection {
      addFeature(geometry = Point(Position(longitude = longitude, latitude = 0.0)))
    }

  private companion object {
    /** Bound on waiting for the map to settle, and then to redraw. */
    const val SETTLE_TIMEOUT_MILLIS = 30_000L
  }
}
