@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
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
import org.maplibre.compose.testing.RecordingList
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry

/** The hoisted entry point on a real map: [rememberMapState] plus the [MaplibreMap] overload. */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapStateEntryTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val runtimeOptions =
    MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun style_content_on_a_remembered_state_reaches_the_engine() = runFfiComposeUiTest {
    val errors = RecordingList<String>()
    val frames = AtomicInt(0)
    lateinit var state: MapState

    setFfiTestMapContent(runtimeOptions) {
      state =
        rememberMapState(baseStyle = BaseStyle.Empty) {
          FillLayer(
            id = "state-entry-fill",
            source =
              rememberGeoJsonSource(
                data = GeoJsonData.Features(FeatureCollection<Geometry, JsonObject?>())
              ),
            color = const(Color.Red),
          )
        }
      MaplibreMap(
        state = state,
        modifier = Modifier,
        logger = Logger.withTag("map-state-entry-test"),
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onFrame = { frames.incrementAndFetch() },
      )
    }

    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { frames.load() > 0 || errors.isNotEmpty() }
    assertTrue(errors.isEmpty(), "The composition reported errors: $errors")

    val session = requireNotNull(state.cameraState.map as? MlnFfiMapCore) { "no session" }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      "state-entry-fill" in session.currentStyleLayerIds()
    }
    assertTrue(errors.isEmpty(), "The composition reported errors: $errors")
  }

  private companion object {
    const val RENDER_TIMEOUT_MILLIS = 30_000L
  }
}
