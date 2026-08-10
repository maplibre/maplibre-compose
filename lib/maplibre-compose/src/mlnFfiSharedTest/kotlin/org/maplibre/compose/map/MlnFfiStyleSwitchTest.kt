@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.CircleLayer
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
 * Rotating the base style with user content composed over it: every composed source and layer has
 * to be re-added, in order, against a base style whose own layers are different.
 */
@OptIn(ExperimentalTestApi::class)
class MlnFfiStyleSwitchTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val runtimeOptions =
    MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun rotating_the_base_style_with_content_composed_over_it() = runFfiComposeUiTest {
    val frames = AtomicInt(0)
    val errors = mutableListOf<String>()
    var loadsFinished = 0
    var style by mutableStateOf(STYLES[0])
    var extraLayer by mutableStateOf(false)
    lateinit var cameraState: CameraState

    setFfiTestMapContent(runtimeOptions) {
      cameraState = rememberCameraState()
      MaplibreMap(
        modifier = Modifier,
        baseStyle = style.base,
        cameraState = cameraState,
        logger = Logger.withTag("style-switch-test"),
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onMapLoadFinished = { loadsFinished++ },
        onFrame = { frames.incrementAndFetch() },
      ) {
        val points = rememberGeoJsonSource(data = GeoJsonData.Features(pointAt(longitude = 0.0)))
        // Two layers on one source at different anchors, so the re-add order matters.
        CircleLayer(id = "user-circles", source = points, color = const(Color.Red))
        // A different base-style anchor per style, so the anchor changes in the same
        // recomposition as the style itself.
        Anchor.At(style.anchor) {
          FillLayer(id = "user-fill", source = points, color = const(Color.Blue))
          // Comes and goes across the rotation, covering removal of a layer that was added while
          // its anchor was unresolvable.
          if (extraLayer) {
            FillLayer(id = "user-extra", source = points, color = const(Color.Green))
          }
        }
      }
    }

    // Each style finishes loading before the next is chosen; switching mid-load is a separate race
    // this test deliberately does not cover.
    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { loadsFinished > 0 && frames.load() > 0 }
    val session = requireNotNull(cameraState.map as? MlnFfiMapSession) { "no desktop session" }
    assertStyleLayers(session, style, extraLayer)

    repeat(ROTATIONS) { round ->
      val loadsBefore = loadsFinished
      val framesBefore = frames.load()
      style = STYLES[(round + 1) % STYLES.size]
      extraLayer = !extraLayer
      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
        loadsFinished > loadsBefore && frames.load() > framesBefore
      }
      assertStyleLayers(session, style, extraLayer)
    }

    assertTrue(errors.isEmpty(), "Rotating the style reported errors: $errors")
  }

  private fun androidx.compose.ui.test.ComposeUiTest.assertStyleLayers(
    session: MlnFfiMapSession,
    style: DemoStyle,
    extraLayer: Boolean,
  ) {
    val expected = buildList {
      add(style.baseLayerIds.first())
      add("user-fill")
      if (extraLayer) add("user-extra")
      add(style.baseLayerIds.last())
      add("user-circles")
    }
    fun relevantLayers(): List<String> =
      session.currentStyleLayerIds().filter { it in RELEVANT_LAYER_IDS }

    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { relevantLayers() == expected }
    assertEquals(expected, relevantLayers(), "live style layer order")
  }

  /** A style and the base-style layer content anchors itself below, as the demo pairs them. */
  private data class DemoStyle(
    val base: BaseStyle,
    val anchor: Anchor,
    val baseLayerIds: List<String>,
  )

  private fun pointAt(longitude: Double): FeatureCollection<Geometry, JsonObject?> =
    buildFeatureCollection {
      addFeature(geometry = Point(Position(longitude = longitude, latitude = 0.0)))
    }

  private companion object {
    const val SETTLE_TIMEOUT_MILLIS = 30_000L

    /** Enough rounds that a fault which needs a second or third switch still shows up. */
    const val ROTATIONS = 6

    val RELEVANT_LAYER_IDS =
      setOf("bg-a", "labels-a", "bg-b", "labels-b", "user-fill", "user-extra", "user-circles")

    /**
     * Styles with different layer sets, so a re-add lands against a different base each time.
     * Inline rather than remote, so the test does not need the network.
     */
    val STYLES =
      listOf(
        DemoStyle(
          base =
            BaseStyle.Json(
              """
              {"version":8,"sources":{},"layers":[
                {"id":"bg-a","type":"background","paint":{"background-color":"#eee"}},
                {"id":"labels-a","type":"background","paint":{"background-color":"#e0e0e0"}}
              ]}
              """
            ),
          anchor = Anchor.Below("labels-a"),
          baseLayerIds = listOf("bg-a", "labels-a"),
        ),
        DemoStyle(
          base =
            BaseStyle.Json(
              """
              {"version":8,"sources":{},"layers":[
                {"id":"bg-b","type":"background","paint":{"background-color":"#ddd"}},
                {"id":"labels-b","type":"background","paint":{"background-color":"#cccccc"}}
              ]}
              """
            ),
          anchor = Anchor.Below("labels-b"),
          baseLayerIds = listOf("bg-b", "labels-b"),
        ),
      )
  }
}
