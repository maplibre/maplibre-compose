package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.LayoutDirection
import co.touchlab.kermit.Logger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.offline.rememberOfflineManager
import org.maplibre.compose.offline.rememberOfflinePacksSource
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/** Composes real maps against the platform's real FFI runtime and rendering host. */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapCompositionTest {

  private val cachePath = FfiTestPlatform.createCachePath()

  private val runtimeOptions =
    MlnFfiRuntimeOptions(cachePath = cachePath, maximumCacheSizeBytes = null)

  /** Camera round trips lose a little precision through the projection; this is generous. */
  private val POSITION_TOLERANCE = 1e-4

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCachePath(cachePath)
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

  /** An empty GeoJSON source on its own, to separate a source-JSON fault from a layer fault. */
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

  /**
   * A layer that leaves and re-enters the composition: a distinct path from the first add, since
   * the layer and its source have to be recreated in the right order.
   */
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
  fun `disposing after replacing the camera state resets the replacement`() {
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

  /**
   * `MaplibreMap` applies the initial camera before the map exists — it is created lazily on the
   * first frame — so this covers the session's deferral and replay of those calls.
   */
  @Test
  fun the_first_camera_position_reaches_the_map() {
    val firstPosition =
      CameraPosition(target = Position(longitude = -122.4194, latitude = 37.7749), zoom = 11.0)
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
  fun `an animation requested as the camera attaches waits for the native map`() {
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

  /**
   * Composes [content] on a bridge-driven map and fails if anything reported an error. The
   * collected errors cover the ones MapLibre reports asynchronously instead of throwing.
   */
  private fun runBridgeMapTest(
    content: @Composable (MutableList<String>, onFrame: () -> Unit) -> Unit
  ) = runBridgeMapTest(body = {}, content = content)

  /** As above, but [body] runs after the first composition settles. */
  private fun runBridgeMapTest(
    body: ComposeUiTest.(MutableList<String>) -> Unit,
    content: @Composable (MutableList<String>, onFrame: () -> Unit) -> Unit,
  ) = runFfiComposeUiTest {
    val errors = CopyOnWriteArrayList<String>()
    val frames = AtomicInteger()
    setFfiTestMapContent(runtimeOptions) { content(errors) { frames.incrementAndGet() } }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { frames.get() > 0 || errors.isNotEmpty() }
    assertTrue(errors.isEmpty(), "The composition reported errors: $errors")
    body(errors)
    waitForIdle()
    assertTrue(errors.isEmpty(), "The composition reported errors: $errors")
    // Without this the test would pass by doing nothing: a map that never gets a frame never
    // creates a runtime or a style.
    assertTrue(frames.get() > 0, "No frame reached MapLibre; the map never rendered.")
  }

  private companion object {
    const val RENDER_TIMEOUT_MILLIS = 30_000L
  }
}
