@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.offline.rememberOfflinePacksSource
import org.maplibre.compose.runtime.MaplibreRuntime
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RecordingList
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/** Composes real maps against the platform's real FFI runtime and rendering host. */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapCompositionTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  private val runtimeOptions =
    MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)

  /** Camera round trips lose a little precision through the projection. */
  private val POSITION_TOLERANCE = 1e-4

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun an_empty_style_composes_without_error() = runBridgeMapTest { errors, onFrame ->
    MaplibreMap(
      state = rememberMapState(baseStyle = BaseStyle.Empty),
      modifier = Modifier,
      logger = Logger.withTag("composition-test"),
      onMapLoadFailed = { errors += "mapLoadFailed: $it" },
      onFrame = { onFrame() },
    )
  }

  /** Style loading needs no rendering, so no frame runs — and none is drawn — before a style. */
  @Test
  fun an_unloaded_style_keeps_the_transparent_load_placeholder() = runFfiComposeUiTest {
    val errors = RecordingList<String>()
    val frames = AtomicInt(0)
    setFfiTestMapContent(runtimeOptions) {
      MaplibreMap(
        state = rememberMapState(baseStyle = BaseStyle.Uri("https://example.invalid/style.json")),
        modifier = Modifier,
        logger = Logger.withTag("composition-test"),
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onFrame = { frames.incrementAndFetch() },
      )
    }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { errors.isNotEmpty() }
    onNodeWithTag(MAP_LOAD_PLACEHOLDER_TAG).assertExists()
    assertEquals(0, frames.load(), "A frame was rendered before the style loaded: $errors")
    assertTrue(errors.any { it.startsWith("mapLoadFailed") }, "The load was not reported: $errors")
  }

  /** The exact shape the offline demo composes, in the no-packs state the screen opens in. */
  @Test
  fun the_offline_demo_layer_composes_without_error() = runBridgeMapTest { errors, onFrame ->
    val state =
      rememberMapState(baseStyle = BaseStyle.Empty) {
        FillLayer(
          id = "offline-packs",
          source = rememberOfflinePacksSource(MaplibreRuntime.offlinePacks),
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
    MaplibreMap(
      state = state,
      modifier = Modifier,
      logger = Logger.withTag("composition-test"),
      onMapLoadFailed = { errors += "mapLoadFailed: $it" },
      onFrame = { onFrame() },
    )
  }

  @Test
  fun an_empty_geojson_source_composes_without_error() = runBridgeMapTest { errors, onFrame ->
    val state =
      rememberMapState(baseStyle = BaseStyle.Empty) {
        FillLayer(
          id = "empty",
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
      logger = Logger.withTag("composition-test"),
      onMapLoadFailed = { errors += "mapLoadFailed: $it" },
      onFrame = { onFrame() },
    )
  }

  @Test
  fun a_layer_removed_and_re_added_comes_back() {
    var visible by mutableStateOf(true)
    lateinit var mapState: MapState
    runBridgeMapTest(
      body = {
        val session =
          requireNotNull(mapState.attachedAdapter as? MlnFfiMapCore) { "no desktop session" }
        waitUntil { "toggled" in session.currentStyleLayerIds() }

        visible = false
        waitUntil { "toggled" !in session.currentStyleLayerIds() }

        visible = true
        waitUntil { "toggled" in session.currentStyleLayerIds() }
      }
    ) { errors, onFrame ->
      mapState =
        rememberMapState(baseStyle = BaseStyle.Empty) {
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
      MaplibreMap(
        state = mapState,
        modifier = Modifier,
        logger = Logger.withTag("composition-test"),
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onFrame = { onFrame() },
      )
    }
  }

  /** The state survives the composable, so disposing the map only detaches the session. */
  @Test
  fun disposing_the_map_detaches_the_session_from_the_state() {
    var visible by mutableStateOf(true)
    val mapState = MapState()
    mapState.baseStyle = BaseStyle.Empty

    runBridgeMapTest(
      body = {
        waitUntil { mapState.isAttached }

        visible = false
        waitUntil { !mapState.isAttached }
        assertFalse(mapState.isAttached)
        mapState.close()
      }
    ) { errors, onFrame ->
      if (visible) {
        MaplibreMap(
          state = mapState,
          modifier = Modifier,
          logger = Logger.withTag("composition-test"),
          onMapLoadFailed = { errors += "mapLoadFailed: $it" },
          onFrame = { onFrame() },
        )
      }
    }
  }

  @Test
  fun changing_layout_direction_keeps_the_live_session_and_host() {
    var layoutDirection by mutableStateOf(LayoutDirection.Ltr)
    val mapState = MapState()
    mapState.baseStyle = BaseStyle.Empty

    runBridgeMapTest(
      body = {
        val session = requireNotNull(mapState.attachedAdapter as? MlnFfiMapCore)

        layoutDirection = LayoutDirection.Rtl
        waitForIdle()

        assertSame(session, mapState.attachedAdapter)
        assertEquals(LayoutDirection.Rtl, session.layoutDirection)
        mapState.close()
      }
    ) { errors, onFrame ->
      CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        MaplibreMap(
          state = mapState,
          modifier = Modifier,
          logger = Logger.withTag("composition-test"),
          onMapLoadFailed = { errors += "mapLoadFailed: $it" },
          onFrame = { onFrame() },
        )
      }
    }
  }

  @Test
  fun the_first_camera_position_reaches_the_map() {
    val firstPosition =
      CameraPosition(
        target = Position(longitude = -122.4194, latitude = 37.7749),
        zoom = 11.0,
        tilt = 35.0,
      )
    lateinit var mapState: MapState

    runBridgeMapTest(
      body = {
        val map =
          requireNotNull(mapState.attachedAdapter) { "The map never reached the camera state" }
        val actual = map.getCameraPosition()
        assertEquals(
          firstPosition.target.longitude,
          actual.target.longitude,
          POSITION_TOLERANCE,
          "longitude",
        )
        assertEquals(
          firstPosition.target.latitude,
          actual.target.latitude,
          POSITION_TOLERANCE,
          "latitude",
        )
        assertEquals(firstPosition.zoom, actual.zoom, POSITION_TOLERANCE, "zoom")
        assertEquals(firstPosition.tilt, actual.tilt, POSITION_TOLERANCE, "tilt")
      }
    ) { errors, onFrame ->
      mapState = rememberMapState(cameraPosition = firstPosition, baseStyle = BaseStyle.Empty)
      MaplibreMap(
        state = mapState,
        modifier = Modifier,
        logger = Logger.withTag("composition-test"),
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onFrame = { onFrame() },
      )
    }
  }

  @Test
  fun an_animation_requested_as_the_camera_attaches_waits_for_the_native_map() {
    val finalPosition =
      CameraPosition(target = Position(longitude = 12.4924, latitude = 41.8902), zoom = 9.0)
    val mapState = MapState()
    mapState.baseStyle = BaseStyle.Empty
    var animationFinished by mutableStateOf(false)

    runBridgeMapTest(
      body = {
        waitUntil(timeoutMillis = 10_000) { animationFinished }
        val actual = requireNotNull(mapState.attachedAdapter).getCameraPosition()
        assertEquals(
          finalPosition.target.longitude,
          actual.target.longitude,
          POSITION_TOLERANCE,
          "longitude",
        )
        assertEquals(
          finalPosition.target.latitude,
          actual.target.latitude,
          POSITION_TOLERANCE,
          "latitude",
        )
        assertEquals(finalPosition.zoom, actual.zoom, POSITION_TOLERANCE, "zoom")
        mapState.close()
      }
    ) { errors, onFrame ->
      MaplibreMap(
        state = mapState,
        modifier = Modifier,
        logger = Logger.withTag("composition-test"),
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onFrame = { onFrame() },
      )
      LaunchedEffect(mapState) {
        mapState.animateCamera(finalPosition, 50.milliseconds)
        animationFinished = true
      }
    }
  }

  @Test
  fun overlay_placed_at_follows_the_camera_target_when_the_map_resizes() {
    // A phone-width activity clamps a 512.dp map, so these widths stay inside 320.dp.
    val mapWidth = mutableStateOf(128.dp)
    val target = Position(longitude = 11.0, latitude = 47.0)

    runBridgeMapTest(
      body = {
        fun centerX(): Float? {
          if (onAllNodesWithTag(PLACED_AT_TAG).fetchSemanticsNodes().isEmpty()) return null
          val bounds = onNodeWithTag(PLACED_AT_TAG).getUnclippedBoundsInRoot()
          return ((bounds.left + bounds.right) / 2).value
        }
        waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
          val x = centerX()
          x != null && abs(x - 64f) < 4f
        }
        val first = requireNotNull(centerX())
        mapWidth.value = 256.dp
        waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
          val x = centerX()
          x != null && abs(x - 128f) < 4f
        }
        val second = requireNotNull(centerX())
        assertTrue(
          abs(second - first * 2f) < 4f,
          "the camera target should stay at the resized center: first=$first second=$second",
        )
      }
    ) { errors, onFrame ->
      MaplibreMap(
        state =
          rememberMapState(
            cameraPosition = CameraPosition(target = target, zoom = 3.0),
            baseStyle = BaseStyle.Empty,
          ),
        modifier = Modifier.width(mapWidth.value).height(256.dp),
        logger = Logger.withTag("composition-test"),
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onFrame = { onFrame() },
        overlay = {
          Box(Modifier.size(4.dp).placedAt(target, Alignment.Center).testTag(PLACED_AT_TAG))
        },
      )
    }
  }

  /** Composes [content] on a bridge-driven map and fails if anything reported an error. */
  private fun runBridgeMapTest(
    content: @Composable (MutableList<String>, onFrame: () -> Unit) -> Unit
  ) = runBridgeMapTest(body = {}, content = content)

  /** As above, but [body] runs after the first composition settles. */
  private fun runBridgeMapTest(
    body: ComposeUiTest.(MutableList<String>) -> Unit,
    content: @Composable (MutableList<String>, onFrame: () -> Unit) -> Unit,
  ) = runFfiComposeUiTest {
    val errors = RecordingList<String>()
    val frames = AtomicInt(0)
    setFfiTestMapContent(runtimeOptions) { content(errors) { frames.incrementAndFetch() } }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { frames.load() > 0 || errors.isNotEmpty() }
    assertTrue(errors.isEmpty(), "The composition reported errors: $errors")
    body(errors)
    waitForIdle()
    assertTrue(errors.isEmpty(), "The composition reported errors: $errors")
    assertTrue(frames.load() > 0, "No frame reached MapLibre; the map never rendered.")
  }

  private companion object {
    const val RENDER_TIMEOUT_MILLIS = 30_000L

    const val PLACED_AT_TAG = "map-placed-at"
  }
}
