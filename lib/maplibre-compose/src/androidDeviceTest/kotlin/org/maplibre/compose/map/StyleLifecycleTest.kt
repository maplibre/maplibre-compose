package org.maplibre.compose.map

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runAndroidComposeUiTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.dsl.featureCollectionOf

@OptIn(ExperimentalTestApi::class)
class StyleLifecycleTest {

  private val styleA = BaseStyle.Json("""{"version":8,"name":"Style A","sources":{},"layers":[]}""")

  private val styleB = BaseStyle.Json("""{"version":8,"name":"Style B","sources":{},"layers":[]}""")

  /** Load empty style, add a LineLayer via content, verify no crash or errors. */
  @Test
  fun loadsEmptyStyleAndAddsLayers() =
    runAndroidComposeUiTest<ComponentActivity> {
      withMap(
        content = {
          val source =
            rememberGeoJsonSource(
              data = GeoJsonData.Features(featureCollectionOf()),
              options = GeoJsonOptions(),
            )
          LineLayer(id = "test-line", source = source)
        }
      ) {
        assertNoLogErrors()
      }
    }

  /** Load empty style, switch to a different JSON style, assert no crash or errors. */
  @Test
  fun switchStyleWithoutCrash() =
    runAndroidComposeUiTest<ComponentActivity> {
      withMap(initialStyle = styleA) {
        switchStyle(styleB)
        waitForStyleReload()
        assertNoLogErrors()
      }
    }

  /** Switch base style 10 times rapidly, assert no crash at the end. */
  @Test
  fun rapidStyleSwitchWithoutCrash() =
    runAndroidComposeUiTest<ComponentActivity> {
      withMap(initialStyle = styleA) {
        repeat(10) { i -> switchStyle(if (i % 2 == 0) styleB else styleA) }
        waitForStyleLoad(15_000)
        assertNoLogErrors()
      }
    }

  /** Add layers, switch style, verify no crash or errors after style reload. */
  @Test
  fun layersRestoredAfterStyleSwitch() =
    runAndroidComposeUiTest<ComponentActivity> {
      withMap(
        initialStyle = styleA,
        content = {
          val source =
            rememberGeoJsonSource(
              data = GeoJsonData.Features(featureCollectionOf()),
              options = GeoJsonOptions(),
            )
          LineLayer(id = "user-line", source = source)
        },
      ) {
        switchStyle(styleB)
        waitForStyleReload()
        assertNoLogErrors()
      }
    }

  /** Call getBaseSource("nonexistent") and verify it returns null without crashing. */
  @Test
  fun getBaseSourceReturnsNull() =
    runAndroidComposeUiTest<ComponentActivity> {
      withMap {
        val source = runOnUiThread { style?.getSource("nonexistent") }
        assertNull(source, "getSource for nonexistent ID should return null")
        assertNoLogErrors()
      }
    }

  /** Add a GeoJsonSource via content, verify styleState.sources contains it. */
  @Test
  fun styleStateSources() =
    runAndroidComposeUiTest<ComponentActivity> {
      withMap(
        content = {
          rememberGeoJsonSource(
            data = GeoJsonData.Features(featureCollectionOf()),
            options = GeoJsonOptions(),
          )
        }
      ) {
        waitUntil(timeoutMillis = 10_000) { styleState.sources.isNotEmpty() }
        assertTrue(styleState.sources.isNotEmpty(), "styleState.sources should contain user source")
        assertNoLogErrors()
      }
    }
}
