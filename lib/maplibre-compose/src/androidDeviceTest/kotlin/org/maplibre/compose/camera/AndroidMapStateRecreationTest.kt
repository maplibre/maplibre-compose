package org.maplibre.compose.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MapRuntime
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.rememberMapRuntime
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.mlnffi.waitUntilLive
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class AndroidMapStateRecreationTest {

  @Test
  fun camera_position_survives_activity_recreation() {
    val cacheFile = FfiTestPlatform.createCacheFile()
    MlnFfiApplication.configure(
      MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)
    )

    try {
      runAndroidComposeUiTest<MapStateRecreationActivity> {
        waitUntilLive(
          "the recreation activity to publish a presentation",
          timeoutMillis = TIMEOUT_MILLIS,
          state = activity?.mapState,
        ) {
          activity?.mapState?.presentation != null
        }
        val firstActivity = requireNotNull(activity)
        val firstState = requireNotNull(firstActivity.mapState)
        assertTrue(firstActivity.defaultRuntimeIsShared)

        runOnIdle { requireNotNull(firstState.presentation).setCameraPosition(EXPECTED_CAMERA) }
        waitUntilLive(
          "the first activity camera to reach the native map",
          timeoutMillis = TIMEOUT_MILLIS,
          state = firstState,
        ) {
          firstState.presentation?.adapter?.hasCamera(EXPECTED_CAMERA) == true
        }

        runOnIdle { firstActivity.recreate() }
        waitUntilLive(
          "the recreated activity to restore the camera",
          timeoutMillis = TIMEOUT_MILLIS,
          state = activity?.mapState,
          extra = { "sameActivity=${activity === firstActivity}" },
        ) {
          activity != null &&
            activity !== firstActivity &&
            activity?.mapState?.presentation?.adapter?.hasCamera(EXPECTED_CAMERA) == true
        }

        val replacementActivity = requireNotNull(activity)
        assertNotSame(firstActivity, replacementActivity, "the activity should have been recreated")
        val restoredState = requireNotNull(replacementActivity.mapState)
        assertNotSame(firstState, restoredState, "restoration should create a new logical map")
        assertCamera(EXPECTED_CAMERA, restoredState.cameraPosition, "restored MapState")
        assertCamera(
          EXPECTED_CAMERA,
          requireNotNull(restoredState.presentation).adapter.getCameraPosition(),
          "replacement native map",
        )
        assertTrue(restoredState.style.baseStyle == BaseStyle.Empty)
        assertTrue(
          restoredState.style.loadState !is StyleLoadState.Failed,
          "the replacement map reported ${restoredState.style.loadState}",
        )
      }
    } finally {
      MlnFfiApplication.resetForTest()
      FfiTestPlatform.deleteCacheFile(cacheFile)
    }
  }

  private companion object {
    const val TIMEOUT_MILLIS = 10_000L
    const val TOLERANCE = 1e-3

    val EXPECTED_CAMERA =
      CameraPosition(
        bearing = 37.0,
        target = Position(longitude = 11.5761, latitude = 48.1371),
        tilt = 42.0,
        zoom = 8.5,
      )

    fun MapAdapter.hasCamera(expected: CameraPosition): Boolean =
      cameraMatches(expected, getCameraPosition())

    fun assertCamera(expected: CameraPosition, actual: CameraPosition, owner: String) {
      assertTrue(
        cameraMatches(expected, actual),
        "$owner should have $expected, but had $actual",
      )
    }

    fun cameraMatches(expected: CameraPosition, actual: CameraPosition): Boolean =
      near(expected.bearing, actual.bearing) &&
        near(expected.target.longitude, actual.target.longitude) &&
        near(expected.target.latitude, actual.target.latitude) &&
        near(expected.tilt, actual.tilt) &&
        near(expected.zoom, actual.zoom)

    fun near(expected: Number, actual: Number): Boolean =
      abs(expected.toDouble() - actual.toDouble()) < TOLERANCE
  }
}

class MapStateRecreationActivity : ComponentActivity() {
  var mapState: MapState? = null
    private set

  var defaultRuntimeIsShared: Boolean = false
    private set

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val firstRuntime: MapRuntime = rememberMapRuntime()
      val secondRuntime: MapRuntime = rememberMapRuntime()
      val state = rememberMapState(firstRuntime, initialBaseStyle = BaseStyle.Empty)
      SideEffect {
        mapState = state
        defaultRuntimeIsShared = firstRuntime === secondRuntime
      }
      MaplibreMap(
        state = state,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}
