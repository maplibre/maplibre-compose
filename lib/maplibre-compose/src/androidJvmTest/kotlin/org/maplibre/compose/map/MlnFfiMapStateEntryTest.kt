@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.camera.CameraPosition
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
import org.maplibre.spatialk.geojson.Position

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

    val session = requireNotNull(state.attachedAdapter as? MlnFfiMapCore) { "no session" }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      "state-entry-fill" in session.currentStyleLayerIds()
    }
    assertTrue(errors.isEmpty(), "The composition reported errors: $errors")
  }

  @Test
  fun a_camera_position_set_before_attach_applies_at_attach() = runFfiComposeUiTest {
    val errors = RecordingList<String>()
    val frames = AtomicInt(0)
    lateinit var state: MapState

    setFfiTestMapContent(runtimeOptions) {
      state = rememberMapState(cameraPosition = FIRST_POSITION, baseStyle = BaseStyle.Empty)
      MaplibreMap(
        state = state,
        modifier = Modifier,
        logger = Logger.withTag("map-state-camera-test"),
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onFrame = { frames.incrementAndFetch() },
      )
    }

    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { frames.load() > 0 || errors.isNotEmpty() }
    assertTrue(errors.isEmpty(), "The composition reported errors: $errors")

    val session = requireNotNull(state.attachedAdapter) { "no session" }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      session.getCameraPosition().isNear(FIRST_POSITION)
    }
  }

  @Test
  fun set_camera_and_animate_camera_move_the_live_map() = runFfiComposeUiTest {
    val errors = RecordingList<String>()
    val frames = AtomicInt(0)
    lateinit var state: MapState

    setFfiTestMapContent(runtimeOptions) {
      state = rememberMapState(baseStyle = BaseStyle.Empty)
      MaplibreMap(
        state = state,
        modifier = Modifier,
        logger = Logger.withTag("map-state-camera-test"),
        onMapLoadFailed = { errors += "mapLoadFailed: $it" },
        onFrame = { frames.incrementAndFetch() },
      )
    }

    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { frames.load() > 0 || errors.isNotEmpty() }
    assertTrue(errors.isEmpty(), "The composition reported errors: $errors")
    val session = requireNotNull(state.attachedAdapter) { "no session" }

    state.setCamera(JUMP_POSITION)
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      session.getCameraPosition().isNear(JUMP_POSITION)
    }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { state.camera.isNear(JUMP_POSITION) }

    // The render loop advances the animation only while the test pumps, so the call runs
    // concurrently.
    val animation =
      CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
        state.animateCamera(ANIMATE_POSITION, duration = 200.milliseconds)
      }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { animation.isCompleted }
    assertFalse(animation.isCancelled, "the animation should complete normally")
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) {
      session.getCameraPosition().isNear(ANIMATE_POSITION)
    }
    waitUntil(timeoutMillis = RENDER_TIMEOUT_MILLIS) { state.camera.isNear(ANIMATE_POSITION) }
  }

  // The skiko StateRestorationTester cannot encode saved state yet, so the test drives a
  // SaveableStateRegistry itself.
  @Test
  fun the_saved_camera_position_restores_into_a_recreated_state() = runFfiComposeUiTest {
    var registry: SaveableStateRegistry? = null
    var savedValues: Map<String, List<Any?>>? = null
    val active = mutableStateOf(true)
    var state: MapState? = null

    // The content leaves and re-enters in place, so rememberSaveable keeps its positional key.
    setContent {
      if (active.value) {
        val currentRegistry = remember { SaveableStateRegistry(savedValues) { true } }
        registry = currentRegistry
        CompositionLocalProvider(LocalSaveableStateRegistry provides currentRegistry) {
          state = rememberMapState(cameraPosition = FIRST_POSITION, baseStyle = BaseStyle.Empty)
        }
      }
    }
    waitForIdle()
    val before = requireNotNull(state) { "no state before restoration" }
    assertEquals(FIRST_POSITION, before.camera)

    before.setCamera(JUMP_POSITION)
    waitForIdle()

    savedValues = requireNotNull(registry) { "no registry" }.performSave()
    active.value = false
    waitForIdle()
    active.value = true
    waitForIdle()

    val after = requireNotNull(state) { "no state after restoration" }
    assertNotSame(before, after, "recreation should construct a new state")
    assertEquals(JUMP_POSITION, after.camera)
  }

  private fun CameraPosition.isNear(other: CameraPosition): Boolean =
    abs(zoom - other.zoom) < 0.01 &&
      abs(target.longitude - other.target.longitude) < 0.01 &&
      abs(target.latitude - other.target.latitude) < 0.01

  private companion object {
    const val RENDER_TIMEOUT_MILLIS = 30_000L

    val FIRST_POSITION =
      CameraPosition(target = Position(longitude = 11.39085, latitude = 47.26266), zoom = 6.0)
    val JUMP_POSITION =
      CameraPosition(target = Position(longitude = -122.4194, latitude = 37.7749), zoom = 5.0)
    val ANIMATE_POSITION =
      CameraPosition(target = Position(longitude = -73.9857, latitude = 40.7484), zoom = 4.0)
  }
}
