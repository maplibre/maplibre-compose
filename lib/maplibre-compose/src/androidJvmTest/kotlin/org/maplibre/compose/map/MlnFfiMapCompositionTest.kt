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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.offline.rememberOfflineManager
import org.maplibre.compose.offline.rememberOfflinePacksSource
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RecordingList
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.dsl.addFeature
import org.maplibre.spatialk.geojson.dsl.buildFeatureCollection

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
      modifier = Modifier,
      baseStyle = BaseStyle.Empty,
      logger = Logger.withTag("composition-test"),
      onMapLoadFailed = { errors += "mapLoadFailed: $it" },
      onFrame = { onFrame() },
    )
  }

  @Test
  fun map_state_renders_a_base_style_and_publishes_one_presentation() = runFfiComposeUiTest {
    val runtime = createNativeMapRuntime(runtimeOptions)
    val state = runtime.createMapState(initialBaseStyle = BaseStyle.Empty)

    setFfiTestMapContent(runtimeOptions) { MaplibreMap(state) }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      state.presentation != null && state.style.loadState == StyleLoadState.Ready
    }

    assertTrue(state.presentation?.isValid == true)
    assertTrue(
      onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty(),
      "the load placeholder should be absent after the base style is ready",
    )

    runtime.close()
    runtime.awaitClosed()
    assertTrue(state.isClosed)
    assertNull(state.presentation)
  }

  @Test
  fun a_map_state_retains_its_native_map_between_presentations() = runFfiComposeUiTest {
    val runtime = createNativeMapRuntime(runtimeOptions)
    val camera = CameraPosition(target = Position(longitude = 11.0, latitude = 47.0), zoom = 6.0)
    val state =
      runtime.createMapState(initialCameraPosition = camera, initialBaseStyle = BaseStyle.Empty)
    var presented by mutableStateOf(true)

    setFfiTestMapContent(runtimeOptions, presentationCount = 2) {
      if (presented) MaplibreMap(state)
    }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      state.presentation != null && state.style.loadState == StyleLoadState.Ready
    }
    val firstPresentation = requireNotNull(state.presentation)
    val firstMap = firstPresentation.adapter

    presented = false
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { state.presentation == null }

    assertTrue(!firstPresentation.isValid)
    assertFailsWith<IllegalStateException> { firstPresentation.setCameraPosition(CameraPosition()) }
    assertEquals(StyleLoadState.Ready, state.style.loadState)
    state.style.baseStyle = BaseStyle.Json("{")
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      state.presentation == null && state.style.loadState is StyleLoadState.Failed
    }
    state.style.baseStyle = RETAINED_STYLE
    assertEquals(StyleLoadState.Loading, state.style.loadState)

    presented = true
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      state.presentation != null && state.style.loadState == StyleLoadState.Ready
    }

    assertSame(firstMap, requireNotNull(state.presentation).adapter)
    assertCameraEquals(camera, state.cameraPosition)
    assertTrue("retained-style" in (firstMap as MlnFfiMapSession).currentStyleLayerIds())

    presented = false
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { state.presentation == null }
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun an_incompatible_scale_factor_replaces_the_native_map_and_replays_state() =
    runFfiComposeUiTest {
      val runtime = createNativeMapRuntime(runtimeOptions)
      val camera =
        CameraPosition(target = Position(longitude = -122.4, latitude = 37.8), zoom = 10.0)
      val state =
        runtime.createMapState(
          initialCameraPosition = camera,
          initialBaseStyle = REPLACEMENT_STYLE,
        )
      var presented by mutableStateOf(true)
      var scaleFactor by mutableStateOf(1f)

      setFfiTestMapContent(runtimeOptions, presentationCount = 2) {
        CompositionLocalProvider(LocalDensity provides Density(scaleFactor)) {
          if (presented) MaplibreMap(state)
        }
      }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.presentation != null && state.style.loadState == StyleLoadState.Ready
      }
      val firstMap = requireNotNull(state.presentation).adapter

      presented = false
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { state.presentation == null }
      scaleFactor = 2f
      presented = true
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.presentation != null && state.style.loadState == StyleLoadState.Ready
      }

      val replacementMap = requireNotNull(state.presentation).adapter
      assertNotSame(firstMap, replacementMap)
      assertCameraEquals(camera, state.cameraPosition)
      assertTrue("replacement-style" in (replacementMap as MlnFfiMapSession).currentStyleLayerIds())

      runtime.close()
      runtime.awaitClosed()
    }

  private fun assertCameraEquals(expected: CameraPosition, actual: CameraPosition) {
    assertEquals(expected.bearing, actual.bearing, POSITION_TOLERANCE, "bearing")
    assertEquals(
      expected.target.longitude,
      actual.target.longitude,
      POSITION_TOLERANCE,
      "longitude",
    )
    assertEquals(expected.target.latitude, actual.target.latitude, POSITION_TOLERANCE, "latitude")
    assertEquals(expected.tilt, actual.tilt, POSITION_TOLERANCE, "tilt")
    assertEquals(expected.zoom, actual.zoom, POSITION_TOLERANCE, "zoom")
  }

  /** Style loading needs no rendering, so no frame runs — and none is drawn — before a style. */
  @Test
  fun an_unloaded_style_keeps_the_transparent_load_placeholder() = runFfiComposeUiTest {
    val errors = RecordingList<String>()
    val frames = AtomicInt(0)
    setFfiTestMapContent(runtimeOptions) {
      MaplibreMap(
        modifier = Modifier,
        baseStyle = BaseStyle.Uri("https://example.invalid/style.json"),
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
    MaplibreMap(
      modifier = Modifier,
      baseStyle = BaseStyle.Empty,
      logger = Logger.withTag("composition-test"),
      onMapLoadFailed = { errors += "mapLoadFailed: $it" },
      onFrame = { onFrame() },
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

  @Test
  fun an_empty_geojson_source_composes_without_error() = runBridgeMapTest { errors, onFrame ->
    MaplibreMap(
      modifier = Modifier,
      baseStyle = BaseStyle.Empty,
      logger = Logger.withTag("composition-test"),
      onMapLoadFailed = { errors += "mapLoadFailed: $it" },
      onFrame = { onFrame() },
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

  @Test
  fun changing_geojson_data_recomposes_and_requests_a_frame() = runFfiComposeUiTest {
    var data by mutableStateOf(pointAt(ORIGIN))
    val errors = RecordingList<String>()
    val frames = AtomicInt(0)
    val camera = CameraState(CameraPosition(target = ORIGIN, zoom = 14.0))

    setFfiTestMapContent(runtimeOptions) {
      MaplibreMap(
        modifier = Modifier.size(128.dp),
        baseStyle = GEOJSON_UPDATE_STYLE,
        cameraState = camera,
        logger = Logger.withTag("geojson-update-test"),
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onFrame = { frames.incrementAndFetch() },
      ) {
        CircleLayer(
          id = "point",
          source = rememberGeoJsonSource(GeoJsonData.Features(data)),
          radius = const(16.dp),
          color = const(Color.Black),
        )
      }
    }

    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { frames.load() > 0 || errors.isNotEmpty() }
    assertTrue(errors.isEmpty(), "The initial point did not render: $errors")
    waitForIdle()
    val framesBeforeUpdate = frames.load()

    data = pointAt(FAR_AWAY)

    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { frames.load() > framesBeforeUpdate }
    assertTrue(errors.isEmpty(), "The GeoJSON update reported errors: $errors")
  }

  @Test
  fun a_layer_removed_and_re_added_comes_back() {
    var visible by mutableStateOf(true)
    lateinit var cameraState: CameraState
    runBridgeMapTest(
      body = {
        val session = requireNotNull(cameraState.map as? MlnFfiMapSession) { "no desktop session" }
        waitUntil { "toggled" in session.currentStyleLayerIds() }

        visible = false
        waitUntil { "toggled" !in session.currentStyleLayerIds() }

        visible = true
        waitUntil { "toggled" in session.currentStyleLayerIds() }
      }
    ) { errors, onFrame ->
      cameraState = rememberCameraState()
      MaplibreMap(
        modifier = Modifier,
        baseStyle = BaseStyle.Empty,
        cameraState = cameraState,
        logger = Logger.withTag("composition-test"),
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onFrame = { onFrame() },
      ) {
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
    }
  }

  @Test
  fun disposing_after_replacing_the_camera_state_resets_the_replacement() {
    var visible by mutableStateOf(true)
    var cameraState by mutableStateOf(CameraState(CameraPosition()))
    val replacement = CameraState(CameraPosition(zoom = 3.0))

    runBridgeMapTest(
      body = {
        waitUntil { cameraState.map != null }
        cameraState = replacement
        waitUntil { replacement.map != null }

        visible = false
        waitUntil { replacement.map == null }
        assertNull(replacement.map)
      }
    ) { errors, onFrame ->
      if (visible) {
        MaplibreMap(
          modifier = Modifier,
          baseStyle = BaseStyle.Empty,
          cameraState = cameraState,
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
    val cameraState = CameraState(CameraPosition())

    runBridgeMapTest(
      body = {
        val session = requireNotNull(cameraState.map as? MlnFfiMapSession)

        layoutDirection = LayoutDirection.Rtl
        waitForIdle()

        assertSame(session, cameraState.map)
        assertEquals(LayoutDirection.Rtl, session.layoutDirection)
      }
    ) { errors, onFrame ->
      CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        MaplibreMap(
          modifier = Modifier,
          baseStyle = BaseStyle.Empty,
          cameraState = cameraState,
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
    lateinit var cameraState: CameraState

    runBridgeMapTest(
      body = {
        val map = requireNotNull(cameraState.map) { "The map never reached the camera state" }
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
      cameraState = rememberCameraState(firstPosition = firstPosition)
      MaplibreMap(
        modifier = Modifier,
        baseStyle = BaseStyle.Empty,
        cameraState = cameraState,
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
    val cameraState = CameraState(CameraPosition())
    var animationFinished by mutableStateOf(false)

    runBridgeMapTest(
      body = {
        waitUntil(timeoutMillis = 10_000) { animationFinished }
        val actual = requireNotNull(cameraState.map).getCameraPosition()
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
      }
    ) { errors, onFrame ->
      MaplibreMap(
        modifier = Modifier,
        baseStyle = BaseStyle.Empty,
        cameraState = cameraState,
        logger = Logger.withTag("composition-test"),
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onFrame = { onFrame() },
      )
      LaunchedEffect(cameraState) {
        cameraState.animateTo(finalPosition, 50.milliseconds)
        animationFinished = true
      }
    }
  }

  @Test
  fun overlay_placed_at_follows_the_camera_target_when_the_map_resizes() {
    // A phone-width activity clamps a 512.dp map, so these widths stay inside 320.dp.
    val mapWidth = mutableStateOf(128.dp)
    val target = Position(longitude = 11.0, latitude = 47.0)
    val camera = CameraState(CameraPosition(target = target, zoom = 3.0))

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
        modifier = Modifier.width(mapWidth.value).height(256.dp),
        baseStyle = BaseStyle.Empty,
        cameraState = camera,
        logger = Logger.withTag("composition-test"),
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onFrame = { onFrame() },
        overlay =
          MapOverlay {
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
    val RETAINED_STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"retained-style","type":"background"}]}"""
      )

    val REPLACEMENT_STYLE =
      BaseStyle.Json(
        """{"version":8,"sources":{},"layers":[{"id":"replacement-style","type":"background"}]}"""
      )

    const val RENDER_TIMEOUT_MILLIS = 30_000L

    const val PLACED_AT_TAG = "map-placed-at"

    val ORIGIN = Position(0.0, 0.0)
    val FAR_AWAY = Position(5.0, 5.0)
    val GEOJSON_UPDATE_STYLE =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"#336699"}}
        ]}
        """
          .trimIndent()
      )
  }
}

private fun pointAt(position: Position): FeatureCollection<Geometry, JsonObject?> =
  buildFeatureCollection {
    addFeature(geometry = Point(position))
  }
