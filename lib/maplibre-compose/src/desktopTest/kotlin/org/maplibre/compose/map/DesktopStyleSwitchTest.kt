package org.maplibre.compose.map

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import co.touchlab.kermit.Logger
import java.nio.file.Files
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
import org.maplibre.compose.mlnffi.HeadlessVulkanMapHostFactory
import org.maplibre.compose.mlnffi.LocalMlnFfiMapHostFactory
import org.maplibre.compose.mlnffi.LocalMlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
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
class DesktopStyleSwitchTest {

  private val cacheDirectory = Files.createTempDirectory("maplibre-style-switch-test")

  private val runtimeOptions =
    MlnFfiRuntimeOptions(
      cachePath = cacheDirectory.resolve("cache.db"),
      maximumCacheSizeBytes = null,
    )

  @AfterTest
  fun cleanUp() {
    cacheDirectory.toFile().deleteRecursively()
  }

  @Test
  fun `rotating the base style with content composed over it`() = runComposeUiTest {
    val factory = HeadlessVulkanMapHostFactory.create()
    val errors = mutableListOf<String>()
    var loadsFinished = 0
    var style by mutableStateOf(STYLES[0])
    var extraLayer by mutableStateOf(false)
    lateinit var cameraState: CameraState

    setContent {
      CompositionLocalProvider(
        LocalMlnFfiMapHostFactory provides factory,
        LocalMlnFfiRuntimeOptions provides runtimeOptions,
      ) {
        cameraState = rememberCameraState()
        MaplibreMap(
          modifier = Modifier,
          baseStyle = style.base,
          cameraState = cameraState,
          logger = Logger.withTag("style-switch-test"),
          onMapLoadFailed = { errors += "mapLoadFailed: $it" },
          onMapLoadFinished = { loadsFinished++ },
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
    }

    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) { factory.created.isNotEmpty() }
    val host = factory.created.single()
    // Each style finishes loading before the next is chosen; switching mid-load is a separate race
    // this test deliberately does not cover.
    waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
      loadsFinished > 0 && host.renderedFrames > 0
    }
    val session = requireNotNull(cameraState.map as? MlnFfiMapSession) { "no desktop session" }
    assertStyleLayers(session, style, extraLayer)

    repeat(ROTATIONS) { round ->
      val loadsBefore = loadsFinished
      val framesBefore = host.renderedFrames
      style = STYLES[(round + 1) % STYLES.size]
      extraLayer = !extraLayer
      waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
        loadsFinished > loadsBefore && host.renderedFrames > framesBefore
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
