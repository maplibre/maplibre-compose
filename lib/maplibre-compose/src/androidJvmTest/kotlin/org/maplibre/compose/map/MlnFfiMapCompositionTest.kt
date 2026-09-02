@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.offline.rememberOfflinePacksSource
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.RasterSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleComposition
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
    TestMap(
      modifier = Modifier,
      baseStyle = BaseStyle.Empty,
      onMapLoadFailed = { errors += "mapLoadFailed: $it" },
      onFrame = { onFrame() },
    )
  }

  @Test
  fun map_state_renders_a_base_style_and_publishes_one_presentation() = runFfiComposeUiTest {
    val runtime = createNativeMapRuntime(runtimeOptions)
    val state = runtime.createMapState(baseStyle = BaseStyle.Empty)

    setFfiTestMapContent(runtimeOptions) { MaplibreMap(state = state) }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
    }

    assertTrue(state.currentMapAttachment?.isValid == true)
    assertTrue(
      onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty(),
      "the load placeholder should be absent after the base style is ready",
    )

    runtime.close()
    runtime.awaitClosed()
    assertTrue(state.isClosed)
    assertNull(state.currentMapAttachment)
  }

  @Test
  fun camera_constraints_update_without_replacing_the_native_map() = runFfiComposeUiTest {
    val runtime = createNativeMapRuntime(runtimeOptions)
    val state =
      runtime.createMapState(
        initialCameraPosition = CameraPosition(zoom = 1.0),
        baseStyle = BaseStyle.Empty,
      )
    var constraints by mutableStateOf(CameraConstraints())

    setFfiTestMapContent(runtimeOptions) {
      MaplibreMap(state = state, cameraConstraints = constraints)
    }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { state.currentMapAttachment != null }
    val session = requireNotNull(state.currentMapAttachment).adapter
    val updated = CameraConstraints(minZoom = 2.0, maxZoom = 18.0, minPitch = 3.0, maxPitch = 45.0)

    constraints = updated
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      session.getCameraPosition().zoom >= updated.minZoom
    }

    assertSame(session, requireNotNull(state.currentMapAttachment).adapter)
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun one_style_composition_is_evaluated_independently_for_two_maps() = runFfiComposeUiTest {
    val runtime = createNativeMapRuntime(runtimeOptions)
    val evaluatorIdentities = mutableSetOf<Any>()
    var showFirst by mutableStateOf(true)
    val style = StyleComposition {
      val evaluatorIdentity = remember { Any() }
      RasterLayer(
        id = "shared-layer",
        source =
          RasterSource(
            "shared-source",
            listOf("https://example.invalid/{z}/{x}/{y}.png"),
          ),
        visible = true,
      )
      DisposableEffect(Unit) {
        evaluatorIdentities += evaluatorIdentity
        onDispose {}
      }
    }
    val first = runtime.createMapState(baseStyle = BaseStyle.Empty, styleComposition = style)
    val second = runtime.createMapState(baseStyle = BaseStyle.Empty, styleComposition = style)

    setFfiTestMapContent(runtimeOptions, presentationCount = 2) {
      if (showFirst) {
        MaplibreMap(state = first)
      } else {
        MaplibreMap(state = second)
      }
    }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      first.currentMapAttachment != null &&
        evaluatorIdentities.size == 1 &&
        first.desiredStyleRevision.layers.any { it.definition.id == "shared-layer" }
    }
    val firstSession = first.currentMapAttachment?.adapter as MlnFfiMapSession
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      "shared-layer" in firstSession.currentStyleLayerIds()
    }

    showFirst = false
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      first.currentMapAttachment == null &&
        second.currentMapAttachment != null &&
        evaluatorIdentities.size == 2
    }
    val secondSession = second.currentMapAttachment?.adapter as MlnFfiMapSession
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      "shared-layer" in secondSession.currentStyleLayerIds()
    }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      first.style.loadState == StyleLoadState.Ready &&
        second.style.loadState == StyleLoadState.Ready
    }
    assertEquals(2, evaluatorIdentities.size)
    assertEquals(
      listOf(StyleLoadState.Ready, StyleLoadState.Ready),
      listOf(first, second).map {
        it.style.loadState
      },
    )

    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun one_style_composition_is_evaluated_independently_for_a_map_and_snapshotter() =
    runFfiComposeUiTest {
      val runtime = createNativeMapRuntime(runtimeOptions)
      val evaluatorIdentities = mutableSetOf<Any>()
      val composition = StyleComposition {
        val evaluatorIdentity = remember { Any() }
        BackgroundLayer(id = "shared-background", color = const(Color.Green))
        DisposableEffect(Unit) {
          evaluatorIdentities += evaluatorIdentity
          onDispose {}
        }
      }
      val state =
        runtime.createMapState(baseStyle = BaseStyle.Empty, styleComposition = composition)

      setFfiTestMapContent(runtimeOptions) { MaplibreMap(state = state) }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.currentMapAttachment != null &&
          state.style.loadState == StyleLoadState.Ready &&
          evaluatorIdentities.size == 1
      }
      val snapshotter = runtime.createSnapshotter(BaseStyle.Empty, composition)
      val image = snapshotter.capture(MapSnapshotRequest(width = 16, height = 16))

      assertEquals(16, image.width)
      assertEquals(16, image.height)
      assertEquals(2, evaluatorIdentities.size)
      snapshotter.close()
      snapshotter.awaitClosed()
      runtime.close()
      runtime.awaitClosed()
    }

  @Test
  fun detached_native_map_keeps_its_applied_revision_until_current_state_is_reattached() =
    runFfiComposeUiTest {
      val runtime = createNativeMapRuntime(runtimeOptions)
      var presented by mutableStateOf(true)
      var latest by mutableStateOf(false)
      val composition = StyleComposition {
        BackgroundLayer(
          id = if (latest) "latest-background" else "initial-background",
          color = const(if (latest) Color.Blue else Color.Red),
        )
      }
      val state =
        runtime.createMapState(baseStyle = BaseStyle.Empty, styleComposition = composition)

      setFfiTestMapContent(runtimeOptions, presentationCount = 2) {
        if (presented) MaplibreMap(state = state)
      }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.style.loadState == StyleLoadState.Ready && state.currentMapAttachment != null
      }
      val session = requireNotNull(state.currentMapAttachment).adapter as MlnFfiMapSession
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        "initial-background" in session.currentStyleLayerIds()
      }

      presented = false
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { state.currentMapAttachment == null }
      latest = true
      assertTrue("initial-background" in session.currentStyleLayerIds())
      assertTrue("latest-background" !in session.currentStyleLayerIds())

      presented = true
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.currentMapAttachment != null &&
          state.desiredStyleRevision.layers.any {
            it.definition.id == "latest-background"
          }
      }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        "latest-background" in session.currentStyleLayerIds() &&
          "initial-background" !in session.currentStyleLayerIds()
      }

      runtime.close()
      runtime.awaitClosed()
    }

  @Test
  fun a_later_revision_supersedes_reconciliation_failure_before_the_surface_is_revealed() =
    runFfiComposeUiTest {
      val runtime = createNativeMapRuntime(runtimeOptions)
      var invalidAnchor by mutableStateOf(true)
      val composition = StyleComposition {
        if (invalidAnchor) {
          Anchor.Below("missing-base-layer") {
            BackgroundLayer(id = "application-background", color = const(Color.Red))
          }
        } else {
          BackgroundLayer(id = "application-background", color = const(Color.Blue))
        }
      }
      val state =
        runtime.createMapState(baseStyle = BaseStyle.Empty, styleComposition = composition)

      setFfiTestMapContent(runtimeOptions) { MaplibreMap(state = state) }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.style.loadState is StyleLoadState.Failed
      }
      assertTrue(
        onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isNotEmpty(),
        "the failed revision must leave the map surface hidden",
      )

      invalidAnchor = false
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.style.loadState == StyleLoadState.Ready
      }
      val session = requireNotNull(state.currentMapAttachment).adapter as MlnFfiMapSession
      assertTrue("application-background" in session.currentStyleLayerIds())
      assertTrue(onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty())

      runtime.close()
      runtime.awaitClosed()
    }

  @Test
  fun a_map_state_retains_its_native_map_between_presentations() = runFfiComposeUiTest {
    val runtime = createNativeMapRuntime(runtimeOptions)
    val camera = CameraPosition(target = Position(longitude = 11.0, latitude = 47.0), zoom = 6.0)
    val state = runtime.createMapState(initialCameraPosition = camera, baseStyle = BaseStyle.Empty)
    var presented by mutableStateOf(true)

    setFfiTestMapContent(runtimeOptions, presentationCount = 2) {
      if (presented) MaplibreMap(state = state)
    }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
    }
    val firstAttachment = requireNotNull(state.currentMapAttachment)
    val firstMap = firstAttachment.adapter

    presented = false
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { state.currentMapAttachment == null }

    assertTrue(!firstAttachment.isValid)
    assertEquals(StyleLoadState.Ready, state.style.loadState)
    state.style.baseStyle = BaseStyle.Json("{")
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      state.currentMapAttachment == null && state.style.loadState is StyleLoadState.Failed
    }
    state.style.baseStyle = RETAINED_STYLE
    assertEquals(StyleLoadState.Loading, state.style.loadState)

    presented = true
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
    }

    assertSame(firstMap, requireNotNull(state.currentMapAttachment).adapter)
    assertCameraEquals(camera, state.cameraPosition)
    assertTrue("retained-style" in (firstMap as MlnFfiMapSession).currentStyleLayerIds())

    presented = false
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { state.currentMapAttachment == null }
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun an_incompatible_presentation_scale_replaces_the_native_map_and_replays_logical_state() =
    runFfiComposeUiTest {
      val runtime = createNativeMapRuntime(runtimeOptions)
      val camera =
        CameraPosition(target = Position(longitude = -122.4, latitude = 37.8), zoom = 10.0)
      val state =
        runtime.createMapState(
          initialCameraPosition = camera,
          baseStyle = REPLACEMENT_STYLE,
        )
      var presented by mutableStateOf(true)
      var scaleFactor by mutableStateOf(1f)

      setFfiTestMapContent(runtimeOptions, presentationCount = 2) {
        CompositionLocalProvider(LocalDensity provides Density(scaleFactor)) {
          if (presented) MaplibreMap(state = state)
        }
      }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
      }
      val firstPresentation = requireNotNull(state.currentMapAttachment)
      val firstMap = firstPresentation.adapter

      presented = false
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { state.currentMapAttachment == null }
      scaleFactor = 2f
      presented = true
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
      }

      val replacementMap = requireNotNull(state.currentMapAttachment).adapter
      assertTrue(!firstPresentation.isValid)
      assertNotSame(firstPresentation, state.currentMapAttachment)
      assertNotSame(firstMap, replacementMap)
      assertCameraEquals(camera, state.cameraPosition)
      assertTrue("replacement-style" in (replacementMap as MlnFfiMapSession).currentStyleLayerIds())
      assertSame(runtime, state.runtime)
      assertTrue(!runtime.isClosed)
      assertTrue(!state.isClosed)

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
      TestMap(
        modifier = Modifier,
        baseStyle = BaseStyle.Uri("https://example.invalid/style.json"),
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
    TestMap(
      modifier = Modifier,
      baseStyle = BaseStyle.Empty,
      onMapLoadFailed = { errors += "mapLoadFailed: $it" },
      onFrame = { onFrame() },
    ) {
      val offlineManager = rememberDefaultMapRuntime().offlineManager
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
    TestMap(
      modifier = Modifier,
      baseStyle = BaseStyle.Empty,
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
    lateinit var mapState: MapState

    setFfiTestMapContent(runtimeOptions) {
      mapState =
        TestMap(
          modifier = Modifier.size(128.dp),
          baseStyle = GEOJSON_UPDATE_STYLE,
          initialCameraPosition = CameraPosition(target = ORIGIN, zoom = 14.0),
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
    lateinit var mapState: MapState
    runBridgeMapTest(
      body = {
        val session =
          requireNotNull(mapState.currentMapAttachment?.adapter as? MlnFfiMapSession) {
            "no native session"
          }
        waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
          "toggled" in session.currentStyleLayerIds()
        }

        visible = false
        waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
          "toggled" !in session.currentStyleLayerIds()
        }

        visible = true
        waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
          "toggled" in session.currentStyleLayerIds()
        }
      }
    ) { errors, onFrame ->
      mapState =
        TestMap(
          modifier = Modifier,
          baseStyle = BaseStyle.Empty,
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
  fun changing_layout_direction_keeps_the_live_session_and_host() {
    var layoutDirection by mutableStateOf(LayoutDirection.Ltr)
    lateinit var mapState: MapState

    runBridgeMapTest(
      body = {
        val session = requireNotNull(mapState.currentMapAttachment?.adapter as? MlnFfiMapSession)

        layoutDirection = LayoutDirection.Rtl
        waitForIdle()

        assertSame(session, mapState.currentMapAttachment?.adapter)
        assertEquals(LayoutDirection.Rtl, session.layoutDirection)
      }
    ) { errors, onFrame ->
      CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        mapState =
          TestMap(
            modifier = Modifier,
            baseStyle = BaseStyle.Empty,
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
          requireNotNull(mapState.currentMapAttachment?.adapter) { "The map never published" }
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
      mapState =
        TestMap(
          modifier = Modifier,
          baseStyle = BaseStyle.Empty,
          initialCameraPosition = firstPosition,
          onMapLoadFailed = { errors += "mapLoadFailed: $it" },
          onFrame = { onFrame() },
        )
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
      TestMap(
        modifier = Modifier.width(mapWidth.value).height(256.dp),
        baseStyle = BaseStyle.Empty,
        initialCameraPosition = CameraPosition(target = target, zoom = 3.0),
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

/** Small test host that observes loading through [MapState.style]. */
@Composable
private fun TestMap(
  baseStyle: BaseStyle,
  modifier: Modifier = Modifier,
  initialCameraPosition: CameraPosition = CameraPosition(),
  onMapLoadFailed: (String?) -> Unit = {},
  onFrame: (Double) -> Unit = {},
  overlay: MapOverlay = MapOverlay.Default,
  content: @Composable () -> Unit = {},
): MapState {
  val state =
    rememberMapState(
      initialCameraPosition = initialCameraPosition,
      baseStyle = baseStyle,
    ) {
      content()
    }
  val loadState = state.style.loadState
  LaunchedEffect(loadState) {
    if (loadState is StyleLoadState.Failed) onMapLoadFailed(loadState.reason)
  }
  MaplibreMap(
    state = state,
    modifier = modifier,
    onFrame = onFrame,
  ) {
    include(overlay)
  }
  return state
}
