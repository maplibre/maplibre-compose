package org.maplibre.compose.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiApplication
import org.maplibre.compose.mlnffi.MlnFfiRuntimeOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class AndroidCameraStateRecreationTest {

  @Test
  fun camera_position_survives_activity_recreation() {
    val cacheFile = FfiTestPlatform.createCacheFile()
    MlnFfiApplication.configure(
      MlnFfiRuntimeOptions(cacheFile = cacheFile, maximumCacheSizeBytes = null)
    )

    try {
      runAndroidComposeUiTest<CameraStateRecreationActivity> {
        waitUntil(timeoutMillis = TIMEOUT_MILLIS) { activity?.cameraState?.map != null }
        val firstActivity = requireNotNull(activity)

        runOnIdle { requireNotNull(firstActivity.cameraState).position = EXPECTED_CAMERA }
        waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
          firstActivity.cameraState?.map?.hasCamera(EXPECTED_CAMERA) == true
        }

        runOnIdle { firstActivity.recreate() }
        waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
          activity != null && activity !== firstActivity && activity?.cameraState?.map != null
        }

        val replacementActivity = requireNotNull(activity)
        assertNotSame(firstActivity, replacementActivity, "the activity should have been recreated")
        val restoredState = requireNotNull(replacementActivity.cameraState)
        assertCamera(EXPECTED_CAMERA, restoredState.position, "restored CameraState")
        assertCamera(
          EXPECTED_CAMERA,
          requireNotNull(restoredState.map).getCameraPosition(),
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
        padding = PaddingValues.Absolute(left = 5.dp, top = 7.dp, right = 11.dp, bottom = 13.dp),
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
        near(expected.zoom, actual.zoom) &&
        near(expected.padding.left(), actual.padding.left()) &&
        near(
          expected.padding.calculateTopPadding().value,
          actual.padding.calculateTopPadding().value,
        ) &&
        near(expected.padding.right(), actual.padding.right()) &&
        near(
          expected.padding.calculateBottomPadding().value,
          actual.padding.calculateBottomPadding().value,
        )

    fun PaddingValues.left(): Float = calculateStartPadding(LayoutDirection.Ltr).value

    fun PaddingValues.right(): Float = calculateEndPadding(LayoutDirection.Ltr).value

    fun near(expected: Number, actual: Number): Boolean =
      abs(expected.toDouble() - actual.toDouble()) < TOLERANCE
  }
}

class CameraStateRecreationActivity : ComponentActivity() {
  var cameraState: CameraState? = null
    private set

  val mapLoadFailures = mutableListOf<String?>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val state = rememberCameraState()
      SideEffect { cameraState = state }
      MaplibreMap(
        modifier = Modifier.fillMaxSize(),
        baseStyle = BaseStyle.Empty,
        cameraState = state,
        onMapLoadFailed = { mapLoadFailures += it },
      )
    }
  }
}
