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
import kotlinx.coroutines.runBlocking
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
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
        waitUntil(timeoutMillis = TIMEOUT_MILLIS) { activity?.mapState?.attachedAdapter != null }
        val firstActivity = requireNotNull(activity)

        runOnIdle {
          runBlocking { requireNotNull(firstActivity.mapState).setCamera(EXPECTED_CAMERA) }
        }
        waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
          firstActivity.mapState?.hasCamera(EXPECTED_CAMERA) == true
        }

        runOnIdle { firstActivity.recreate() }
        waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
          activity != null &&
            activity !== firstActivity &&
            activity?.mapState?.hasCamera(EXPECTED_CAMERA) == true
        }

        val replacementActivity = requireNotNull(activity)
        assertNotSame(firstActivity, replacementActivity, "the activity should have been recreated")
        val restoredState = requireNotNull(replacementActivity.mapState)
        assertCamera(EXPECTED_CAMERA, restoredState.camera, "restored MapState")
        // The live map, which only the internal adapter answers for.
        assertCamera(
          EXPECTED_CAMERA,
          requireNotNull(restoredState.attachedAdapter).getCameraPosition(),
          "replacement native map",
        )
        assertTrue(
          replacementActivity.mapLoadFailures.isEmpty(),
          "the replacement map reported load failures: ${replacementActivity.mapLoadFailures}",
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

    fun MapState.hasCamera(expected: CameraPosition): Boolean =
      attachedAdapter?.hasCamera(expected) == true

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

  val mapLoadFailures = mutableListOf<String?>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val state = rememberMapState(baseStyle = BaseStyle.Empty)
      SideEffect { mapState = state }
      MaplibreMap(
        state = state,
        modifier = Modifier.fillMaxSize(),
        onMapLoadFailed = { mapLoadFailures += it },
      )
    }
  }
}
