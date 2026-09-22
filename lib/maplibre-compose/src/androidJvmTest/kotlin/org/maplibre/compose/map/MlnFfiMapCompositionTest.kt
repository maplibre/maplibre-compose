@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.DragResponse
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.compose.layers.BackgroundLayer
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.RasterLayer
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.overlay.MapOverlay
import org.maplibre.compose.overlay.include
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.RasterTileSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Position

/** Composes real maps against the platform's real FFI runtime and rendering host. */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapCompositionTest {

  private val cacheFile = FfiTestPlatform.createCacheFile()

  // Compose and the test drive this map from different threads in the desktop and device
  // harnesses, so the runtime opts out of main-thread confinement here.
  private val runtimeOptions =
    MapRuntimeOptions(cacheFile = cacheFile, mainDispatcher = UnconfinedTestMain)

  /** Camera round trips lose a little precision through the projection. */
  private val POSITION_TOLERANCE = 1e-4

  @AfterTest
  fun cleanUp() {
    FfiTestPlatform.deleteCacheFile(cacheFile)
  }

  @Test
  fun child_claims_geometry_contacts_and_declines_background_to_ancestor_map() =
    runFfiComposeUiTest {
      fun point(x: Float, y: Float) = Offset(x * density.density, y * density.density)
      withTestRuntime(runtimeOptions) { runtime ->
        val state =
          runtime.createMapState(BaseStyle.Empty, cameraPosition = CameraPosition(zoom = 12.0))
        var claims = 0
        var buttonClicks = 0
        var placements = 0
        setFfiTestMapContent(runtimeOptions) {
          MaplibreMap(
            modifier = Modifier.size(300.dp).testTag("map"),
            state = state,
            interactions =
              MapInteractions {
                callbacks {
                  click {
                    onUnhandled {
                      placements++
                      ClickResult.Consume
                    }
                  }
                }
              },
            overlay = {
              Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                  awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (
                      !down.isConsumed &&
                        down.position.x < 80.dp.toPx() &&
                        down.position.y < 80.dp.toPx()
                    ) {
                      claims++
                      down.consume()
                      do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                      } while (event.changes.any { it.pressed })
                    }
                  }
                }
              )
              Box(Modifier.size(30.dp).testTag("button").clickable { buttonClicks++ })
            },
          )
        }
        waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
          state.style.loadState == StyleLoadState.Ready &&
            state.currentMapAttachment?.viewport != null &&
            onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty()
        }
        val before = state.cameraPosition
        onNodeWithTag("map").performMouseInput {
          moveTo(point(60f, 60f))
          press()
          moveBy(point(40f, 0f))
          release()
        }
        waitForIdle()
        assertEquals(1, claims)
        assertEquals(before, state.cameraPosition)
        onNodeWithTag("button").performMouseInput { click(center) }
        waitForIdle()
        assertEquals(1, buttonClicks)
        assertEquals(1, claims)
        assertEquals(0, placements)
        onNodeWithTag("map").performMouseInput { exit() }
        onNodeWithTag("map").performTouchInput {
          down(0, point(60f, 60f))
          down(1, point(100f, 100f))
          moveTo(0, point(40f, 40f))
          moveTo(1, point(160f, 120f))
          up(0)
          up(1)
        }
        waitForIdle()
        assertEquals(2, claims)
        assertEquals(before, state.cameraPosition)
        onNodeWithTag("map").performMouseInput { click(point(200f, 200f)) }
        waitUntil(timeoutMillis = 5_000L) { placements == 1 }
        onNodeWithTag("map").performMouseInput {
          // Keep this drag outside the preceding click's double-click slop.
          moveTo(point(200f, 120f))
          press()
          moveBy(point(40f, 0f))
          release()
        }
        waitUntil(timeoutMillis = 5_000L) { state.cameraPosition.target != before.target }
        assertEquals(1, placements)
        val zoomBefore = state.cameraPosition.zoom
        onNodeWithTag("map").performMouseInput { exit() }
        onNodeWithTag("map").performTouchInput {
          down(0, point(120f, 160f))
          down(1, point(180f, 160f))
          moveTo(0, point(100f, 160f))
          moveTo(1, point(200f, 160f))
          moveTo(0, point(80f, 160f))
          moveTo(1, point(220f, 160f))
          up(0)
          up(1)
        }
        waitUntil(timeoutMillis = 5_000L) { state.cameraPosition.zoom > zoomBefore }
      }
    }

  @Test
  fun gradual_compose_drag_claims_before_map_navigation() = runFfiComposeUiTest {
    fun point(x: Float, y: Float) = Offset(x * density.density, y * density.density)
    withTestRuntime(runtimeOptions) { runtime ->
      val state =
        runtime.createMapState(BaseStyle.Empty, cameraPosition = CameraPosition(zoom = 12.0))
      var drags = 0
      var taps = 0
      val mapFocused = AtomicBoolean(false)
      setFfiTestMapContent(runtimeOptions) {
        MaplibreMap(
          Modifier.size(300.dp).testTag("map").onFocusChanged { mapFocused.store(it.isFocused) },
          state = state,
          interactions =
            MapInteractions {
              callbacks {
                click {
                  onUnhandled {
                    taps++
                    ClickResult.Consume
                  }
                }
              }
            },
          overlay = {
            Box(
              Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures { change, _ ->
                  change.consume()
                  drags++
                }
              }
            )
          },
        )
      }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.style.loadState == StyleLoadState.Ready &&
          state.currentMapAttachment?.viewport != null &&
          onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty()
      }
      val before = state.cameraPosition
      assertFalse(mapFocused.load())
      assertFalse(state.isEngaged)
      onNodeWithTag("map").performTouchInput {
        down(point(150f, 150f))
        repeat(20) { moveBy(point(2f, 0f)) }
        up()
      }
      waitForIdle()
      assertTrue(drags > 0)
      assertEquals(before, state.cameraPosition)
      assertFalse(mapFocused.load(), "a descendant drag focused the map")
      assertFalse(state.isEngaged, "a descendant drag engaged the map")
      assertEquals(0, taps)

      // A completed map tap remains valid when the child claims a nearby second contact.
      mainClock.autoAdvance = false
      try {
        onNodeWithTag("map").performTouchInput {
          advanceEventTime(1_000)
          down(point(150f, 150f))
          up()
          advanceEventTime(80)
          down(point(150f, 150f))
          repeat(10) { moveBy(point(4f, 0f)) }
          up()
        }
        mainClock.advanceTimeBy(1_000)
        waitUntil(timeoutMillis = 5_000) { taps == 1 }
        assertEquals(before, state.cameraPosition)
      } finally {
        mainClock.autoAdvance = true
      }
    }
  }

  @Test
  fun gradual_compose_transforms_claim_before_map_navigation() = runFfiComposeUiTest {
    fun point(x: Float, y: Float) = Offset(x * density.density, y * density.density)
    withTestRuntime(runtimeOptions) { runtime ->
      val state =
        runtime.createMapState(BaseStyle.Empty, cameraPosition = CameraPosition(zoom = 12.0))
      var pans = 0
      var pinches = 0
      var twists = 0
      setFfiTestMapContent(runtimeOptions) {
        MaplibreMap(
          Modifier.size(300.dp).testTag("map"),
          state = state,
          overlay = {
            Box(
              Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, rotation ->
                  if (pan != Offset.Zero) pans++
                  if (zoom != 1f) pinches++
                  if (rotation != 0f) twists++
                }
              }
            )
          },
        )
      }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.style.loadState == StyleLoadState.Ready &&
          state.currentMapAttachment?.viewport != null &&
          onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty()
      }
      val before = state.cameraPosition
      // Small steps cross the map's former component thresholds before host touch slop.
      // Move both contacts in one event so pair motion does not include artificial pan/twist.
      for (kind in 0..2) {
        pans = 0
        pinches = 0
        twists = 0
        onNodeWithTag("map").performTouchInput {
          down(0, point(90f, 150f))
          down(1, point(210f, 150f))
          repeat(40) { index ->
            val step = index + 1f
            val centerX = 150f + if (kind == 0) step else 0f
            val radius = 60f + if (kind == 1) step else 0f
            val angle = if (kind == 2) step * 0.75f * kotlin.math.PI / 180.0 else 0.0
            val dx = (radius * cos(angle)).toFloat()
            val dy = (radius * sin(angle)).toFloat()
            updatePointerTo(0, point(centerX - dx, 150f - dy))
            updatePointerTo(1, point(centerX + dx, 150f + dy))
            move()
          }
          up(0)
          up(1)
        }
        waitForIdle()
        assertEquals(before, state.cameraPosition, "camera changed during transform $kind")
        when (kind) {
          0 -> assertTrue(pans > 0, "child received no pan")
          1 -> assertTrue(pinches > 0, "child received no pinch")
          2 -> assertTrue(twists > 0, "child received no twist")
        }
      }
    }
  }

  @Test
  fun replacing_renderer_density_preserves_overlay_composition() = runFfiComposeUiTest {
    withTestRuntime(runtimeOptions) { runtime ->
      val state = runtime.createMapState(baseStyle = BaseStyle.Empty)
      var scale by mutableStateOf(1f)
      var overlayIdentity: Any? = null
      val disposed = AtomicInt(0)
      setFfiTestMapContent(runtimeOptions, presentationCount = 2) {
        CompositionLocalProvider(LocalDensity provides Density(scale)) {
          MaplibreMap(
            state = state,
            modifier = Modifier.size(200.dp),
            overlay = {
              val identity = remember { Any() }
              overlayIdentity = identity
              DisposableEffect(Unit) { onDispose { disposed.incrementAndFetch() } }
            },
          )
        }
      }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.style.loadState == StyleLoadState.Ready &&
          onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty()
      }
      val originalOverlay = overlayIdentity
      val originalSession = state.currentMapAttachment?.adapter
      runOnUiThread { scale = 2f }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.currentMapAttachment?.adapter != null &&
          state.currentMapAttachment?.adapter !== originalSession &&
          state.style.loadState == StyleLoadState.Ready &&
          onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty()
      }
      assertSame(originalOverlay, overlayIdentity)
      assertEquals(0, disposed.load())
    }
  }

  @Test
  fun a_pitched_pan_continues_in_its_release_direction_without_changing_the_camera_pose() =
    runFfiComposeUiTest {
      withTestRuntime(runtimeOptions) { runtime ->
        val start = CameraPosition(target = Position(0.0, 0.0), zoom = 12.0, tilt = 60.0)
        val state = runtime.createMapState(baseStyle = BaseStyle.Empty, cameraPosition = start)
        var configuration by mutableStateOf(MapInteractions.Standard)
        var uiOptions by mutableStateOf(MapUiOptions.None)
        var density = 1f
        setFfiTestMapContent(runtimeOptions) {
          density = LocalDensity.current.density
          MaplibreMap(
            modifier = Modifier.size(300.dp).testTag("pitched-fling-map"),
            state = state,
            interactions = configuration,
            uiOptions = uiOptions,
          )
        }
        waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
          state.style.loadState == StyleLoadState.Ready &&
            state.currentMapAttachment?.viewport != null
        }
        val map = onNodeWithTag("pitched-fling-map")

        fun pan(direction: Float, withFling: Boolean): Float {
          runOnUiThread {
            configuration = MapInteractions {
              camera {
                pan {
                  momentum {
                    enabled = withFling
                    durationScale = 0.25
                  }
                }
              }
            }
            uiOptions =
              MapUiOptions(MapUiOptions.None) {
                bindings {
                  drag {
                    enabled = true
                    mappings { on(button = PointerButton.Primary, response = DragResponse.Pan) }
                  }
                }
              }
            state.setCameraPosition(start)
          }
          waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
            val camera = state.cameraPosition
            abs(camera.target.latitude) < 1e-8 &&
              abs(camera.target.longitude) < 1e-8 &&
              !state.isCameraMoving &&
              state.cameraMoveReason == CameraMoveReason.PROGRAMMATIC
          }
          map.performTouchInput {
            down(center)
            repeat(4) { moveBy(Offset(0f, direction * 16f * density), delayMillis = 8) }
            up()
          }
          waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
            state.cameraMoveReason == CameraMoveReason.GESTURE && !state.isCameraMoving
          }
          val camera = state.cameraPosition
          assertEquals(start.zoom, camera.zoom, 1e-6)
          assertEquals(start.bearing, camera.bearing, 1e-6)
          assertEquals(start.tilt, camera.tilt, 1e-6)
          return checkNotNull(state.screenLocationFromPosition(start.target)).y.value
        }

        for (direction in listOf(-1f, 1f)) {
          val withoutMomentum = pan(direction, withFling = false)
          val withMomentum = pan(direction, withFling = true)
          val extraTravel = direction * (withMomentum - withoutMomentum)
          assertTrue(
            extraTravel > 5f,
            "release added no travel in direction $direction: $extraTravel dp",
          )
          assertTrue(extraTravel < 150f, "pitched continuation jumped by $extraTravel dp")
        }
      }
    }

  @Test
  fun map_state_renders_a_base_style_and_publishes_one_presentation() = runFfiComposeUiTest {
    withTestRuntime(runtimeOptions) { runtime ->
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
  }

  @Test
  fun focus_modifiers_on_the_map_modifier_reach_the_input_node() = runFfiComposeUiTest {
    withTestRuntime(runtimeOptions) { runtime ->
      val state = runtime.createMapState(baseStyle = BaseStyle.Empty)
      val focusRequester = FocusRequester()
      val hasFocus = AtomicBoolean(false)

      setFfiTestMapContent(runtimeOptions) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        MaplibreMap(
          modifier =
            Modifier.focusRequester(focusRequester).onFocusChanged { hasFocus.store(it.hasFocus) },
          state = state,
          overlay = {},
        )
      }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.currentMapAttachment != null &&
          state.style.loadState == StyleLoadState.Ready &&
          onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty()
      }

      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { hasFocus.load() }
      onNodeWithContentDescription("Map").assertIsFocused()
      assertFalse(state.isEngaged, "a focus request engaged the map")
    }
  }

  @Test
  fun camera_constraints_update_without_replacing_the_native_map() = runFfiComposeUiTest {
    withTestRuntime(runtimeOptions) { runtime ->
      val state =
        runtime.createMapState(
          cameraPosition = CameraPosition(zoom = 1.0),
          baseStyle = BaseStyle.Empty,
        )
      var constraints by mutableStateOf(CameraConstraints())

      setFfiTestMapContent(runtimeOptions) {
        MaplibreMap(state = state, cameraConstraints = constraints)
      }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { state.currentMapAttachment != null }
      val session = requireNotNull(state.currentMapAttachment).adapter
      val updated =
        CameraConstraints(minZoom = 2.0, maxZoom = 18.0, minPitch = 3.0, maxPitch = 45.0)

      constraints = updated
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        session.getCameraPosition().zoom >= updated.minZoom
      }

      assertSame(session, requireNotNull(state.currentMapAttachment).adapter)
    }
  }

  @Test
  fun one_style_composition_is_evaluated_independently_for_two_maps() = runFfiComposeUiTest {
    withTestRuntime(runtimeOptions) { runtime ->
      val evaluatorIdentities = mutableSetOf<Any>()
      var showFirst by mutableStateOf(true)
      val content: @Composable @MaplibreComposable () -> Unit = {
        val evaluatorIdentity = remember { Any() }
        RasterLayer(
          id = "shared-layer",
          source =
            RasterTileSource(
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
      val first = runtime.createMapState(baseStyle = BaseStyle.Empty, content = content)
      val second = runtime.createMapState(baseStyle = BaseStyle.Empty, content = content)

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
          first.styleAuthority.desiredStyleRevision.layers.any {
            it.definition.id == "shared-layer"
          }
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
    }
  }

  @Test
  fun one_style_composition_is_evaluated_independently_for_a_map_and_snapshotter() =
    runFfiComposeUiTest {
      withTestRuntime(runtimeOptions) { runtime ->
        val evaluatorIdentities = mutableSetOf<Any>()
        val content: @Composable @MaplibreComposable () -> Unit = {
          val evaluatorIdentity = remember { Any() }
          BackgroundLayer(id = "shared-background", color = const(Color.Green))
          DisposableEffect(Unit) {
            evaluatorIdentities += evaluatorIdentity
            onDispose {}
          }
        }
        val state = runtime.createMapState(baseStyle = BaseStyle.Empty, content = content)

        setFfiTestMapContent(runtimeOptions) { MaplibreMap(state = state) }
        waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
          state.currentMapAttachment != null &&
            state.style.loadState == StyleLoadState.Ready &&
            evaluatorIdentities.size == 1
        }
        val snapshotter = runtime.createSnapshotter(BaseStyle.Empty, content)
        val image = snapshotter.capture(MapSnapshotRequest(width = 16, height = 16))

        assertEquals(16, image.width)
        assertEquals(16, image.height)
        assertEquals(2, evaluatorIdentities.size)
        snapshotter.close()
        snapshotter.awaitClosed()
      }
    }

  @Test
  fun detached_native_map_keeps_its_applied_revision_until_current_state_is_reattached() =
    runFfiComposeUiTest {
      withTestRuntime(runtimeOptions) { runtime ->
        var presented by mutableStateOf(true)
        var latest by mutableStateOf(false)
        val state =
          runtime.createMapState(baseStyle = BaseStyle.Empty) {
            BackgroundLayer(
              id = if (latest) "latest-background" else "initial-background",
              color = const(if (latest) Color.Blue else Color.Red),
            )
          }

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
            state.styleAuthority.desiredStyleRevision.layers.any {
              it.definition.id == "latest-background"
            }
        }
        waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
          "latest-background" in session.currentStyleLayerIds() &&
            "initial-background" !in session.currentStyleLayerIds()
        }
      }
    }

  @Test
  fun a_later_revision_supersedes_reconciliation_failure_before_the_surface_is_revealed() =
    runFfiComposeUiTest {
      withTestRuntime(runtimeOptions) { runtime ->
        // Synchronous GeoJSON parsing rejects malformed data inside the revision itself.
        var malformedData by mutableStateOf(true)
        val state =
          runtime.createMapState(baseStyle = BaseStyle.Empty) {
            val points =
              rememberGeoJsonSource(
                data = GeoJsonData.JsonString(if (malformedData) "{" else EMPTY_FEATURE_COLLECTION),
                options = GeoJsonOptions(synchronousUpdate = true),
              )
            CircleLayer(id = "application-circles", source = points, color = const(Color.Red))
          }

        setFfiTestMapContent(runtimeOptions) { MaplibreMap(state = state) }
        waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
          state.style.loadState is StyleLoadState.Failed
        }
        assertTrue(
          onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isNotEmpty(),
          "the failed revision must leave the map surface hidden",
        )
        onNodeWithContentDescription("Map").assertExists("a map without a style has no semantics")

        malformedData = false
        waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
          state.style.loadState == StyleLoadState.Ready
        }
        val session = requireNotNull(state.currentMapAttachment).adapter as MlnFfiMapSession
        assertTrue("application-circles" in session.currentStyleLayerIds())
        assertTrue(onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty())
      }
    }

  @Test
  fun a_map_state_retains_its_native_map_between_presentations() = runFfiComposeUiTest {
    withTestRuntime(runtimeOptions) { runtime ->
      val camera = CameraPosition(target = Position(longitude = 11.0, latitude = 47.0), zoom = 6.0)
      val state = runtime.createMapState(cameraPosition = camera, baseStyle = BaseStyle.Empty)
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
      state.style.asMutable!!.baseStyle = BaseStyle.Json("{")
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.currentMapAttachment == null && state.style.loadState is StyleLoadState.Failed
      }
      state.style.asMutable!!.baseStyle = RETAINED_STYLE
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
    }
  }

  @Test
  fun an_incompatible_presentation_scale_replaces_the_native_map_and_replays_logical_state() =
    runFfiComposeUiTest {
      withTestRuntime(runtimeOptions) { runtime ->
        val camera =
          CameraPosition(target = Position(longitude = -122.4, latitude = 37.8), zoom = 10.0)
        val state =
          runtime.createMapState(
            cameraPosition = camera,
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
        assertTrue(
          "replacement-style" in (replacementMap as MlnFfiMapSession).currentStyleLayerIds()
        )
        assertSame(runtime, state.runtime)
        assertTrue(!runtime.isClosed)
        assertTrue(!state.isClosed)
      }
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

  @Test
  fun a_base_style_switch_does_not_cover_the_map_with_the_load_placeholder() = runFfiComposeUiTest {
    withTestRuntime(runtimeOptions) { runtime ->
      val first =
        BaseStyle.Json(
          """{"version":8,"sources":{},"layers":[{"id":"bg-a","type":"background"}]}"""
        )
      val second =
        BaseStyle.Json(
          """{"version":8,"sources":{},"layers":[{"id":"bg-b","type":"background"}]}"""
        )
      val state = runtime.createMapState(baseStyle = first)

      setFfiTestMapContent(runtimeOptions) { MaplibreMap(state = state) }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
      }
      val session = requireNotNull(state.currentMapAttachment).adapter as MlnFfiMapSession
      assertTrue(session.canPresentFrames)
      assertTrue(onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty())

      runOnUiThread { state.style.asMutable!!.baseStyle = second }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { state.style.baseStyle == second }
      assertTrue(
        session.canPresentFrames,
        "a later style switch must keep the first loaded style on screen",
      )
      assertTrue(
        onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty(),
        "a style switch must not cover the map with the load placeholder",
      )
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.style.loadState == StyleLoadState.Ready && "bg-b" in session.currentStyleLayerIds()
      }
      assertTrue(onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty())
    }
  }

  @Test
  fun a_failed_replacement_style_hides_the_native_map() = runFfiComposeUiTest {
    withTestRuntime(runtimeOptions) { runtime ->
      val state = runtime.createMapState(baseStyle = BaseStyle.Empty)

      setFfiTestMapContent(runtimeOptions) { MaplibreMap(state = state) }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.currentMapAttachment != null && state.style.loadState == StyleLoadState.Ready
      }
      val session = requireNotNull(state.currentMapAttachment).adapter as MlnFfiMapSession
      assertTrue(session.canPresentFrames)

      runOnUiThread { state.style.asMutable!!.baseStyle = BaseStyle.Json("{") }
      waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
        state.style.loadState is StyleLoadState.Failed
      }

      assertTrue(!session.canPresentFrames)
      onNodeWithTag(MAP_LOAD_PLACEHOLDER_TAG).assertExists()
    }
  }

  /** Style loading must not run or draw a frame before a style is available. */
  @Test
  fun an_unloaded_style_keeps_the_transparent_load_placeholder() = runFfiComposeUiTest {
    val errors = RecordingList<String>()
    lateinit var mapState: MapState
    var baseStyle: BaseStyle by mutableStateOf(BaseStyle.Uri("https://example.invalid/style.json"))
    setFfiTestMapContent(runtimeOptions) {
      mapState =
        TestMap(
          modifier = Modifier,
          baseStyle = baseStyle,
          onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        )
    }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { errors.isNotEmpty() }
    onNodeWithTag(MAP_LOAD_PLACEHOLDER_TAG).assertExists()
    // The session, because an event collector misses a frame that renders before it subscribes.
    val session = mapState.currentMapAttachment?.adapter as? MlnFfiMapSession
    assertFalse(
      session?.hasRenderedAFrame == true,
      "A frame was rendered before the style loaded: $errors",
    )
    assertTrue(errors.any { it.startsWith("mapLoadFailed") }, "The load was not reported: $errors")

    val before = mapState.cameraPosition.target
    onNodeWithTag(MAP_LOAD_PLACEHOLDER_TAG).performTouchInput { down(center) }
    runOnUiThread { baseStyle = BaseStyle.Empty }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      mapState.style.loadState == StyleLoadState.Ready &&
        onAllNodesWithTag(MAP_LOAD_PLACEHOLDER_TAG).fetchSemanticsNodes().isEmpty()
    }
    onNodeWithContentDescription("Map").performTouchInput {
      moveBy(Offset(60f, 0f))
      up()
    }
    waitForIdle()
    assertEquals(before, mapState.cameraPosition.target, "a loading contact became a map drag")
    onNodeWithContentDescription("Map").performTouchInput {
      down(center)
      moveBy(Offset(60f, 0f))
      up()
    }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { mapState.cameraPosition.target != before }
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
            Box(
              Modifier.placedAt(target, alignment = Alignment.Center)
                .size(4.dp)
                .testTag(PLACED_AT_TAG)
            )
          },
      )
    }
  }

  /** Composes [content], then runs [body] after the first frame, failing on any reported error. */
  private fun runBridgeMapTest(
    body: ComposeUiTest.(RecordingList<String>) -> Unit = {},
    content: @Composable (RecordingList<String>, onFrame: () -> Unit) -> Unit,
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

    const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

    const val RENDER_TIMEOUT_MILLIS = 30_000L

    const val PLACED_AT_TAG = "map-placed-at"
  }
}

/** Small test host that observes loading through [MapState.style]. */
@Composable
private fun TestMap(
  baseStyle: BaseStyle,
  modifier: Modifier = Modifier,
  initialCameraPosition: CameraPosition = CameraPosition(),
  onMapLoadFailed: (String?) -> Unit = {},
  onFrame: () -> Unit = {},
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
  LaunchedEffect(state) {
    state.events.collect { if (it is MapEvent.FrameRendered) onFrame() }
  }
  MaplibreMap(
    state = state,
    modifier = modifier,
  ) {
    include(overlay)
  }
  return state
}
