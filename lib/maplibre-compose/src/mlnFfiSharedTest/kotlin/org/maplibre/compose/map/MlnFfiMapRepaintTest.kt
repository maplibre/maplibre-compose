@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
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
 * Proves that a style change actually redraws, not just that it reaches MapLibre.
 *
 * `addSource`, `removeSource`, and `removeImage` publish no render update on their own, so the
 * style binding requests a repaint for them. Each test settles first, because a frame still in
 * flight would render the mutation by accident.
 */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapRepaintTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val runtimeOptions =
    MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun adding_a_layer_after_the_map_settles_redraws() {
    var visible by mutableStateOf(false)
    runRepaintTest(mutate = { visible = true }) { ToggledLayer(visible) }
  }

  @Test
  fun removing_a_layer_after_the_map_settles_redraws() {
    var visible by mutableStateOf(true)
    runRepaintTest(mutate = { visible = false }) { ToggledLayer(visible) }
  }

  @Test
  fun replacing_source_data_after_the_map_settles_redraws() {
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
   * The assertion counts frames MapLibre rendered into, not frames acquired: the host hands out a
   * frame whenever Compose draws.
   */
  private fun runRepaintTest(mutate: ComposeUiTest.() -> Unit, content: @Composable () -> Unit) =
    runFfiComposeUiTest {
      val frames = AtomicInt(0)

      setFfiTestMapContent(runtimeOptions) {
        MaplibreMap(
          modifier = Modifier,
          baseStyle = BaseStyle.Empty,
          logger = Logger.withTag("repaint-test"),
          onFrame = { frames.incrementAndFetch() },
          content = content,
        )
      }

      // The map advances on a thread of its own, so settling means observing a real quiet window.
      // Comparing two adjacent reads can succeed before an already-queued startup frame lands.
      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { frames.load() > 0 }
      var before = frames.load()
      var unchangedSince = TimeSource.Monotonic.markNow()
      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
        val current = frames.load()
        if (current != before) {
          before = current
          unchangedSince = TimeSource.Monotonic.markNow()
        }
        unchangedSince.elapsedNow() >= QUIET_WINDOW
      }

      mutate()

      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { frames.load() > before }
      assertTrue(
        frames.load() > before,
        "The change produced no new rendered frame ($before before, ${frames.load()} " +
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
    const val SETTLE_TIMEOUT_MILLIS = 30_000L

    /** Long enough to outlast work already queued by the initial style composition. */
    val QUIET_WINDOW = 250.milliseconds
  }
}
