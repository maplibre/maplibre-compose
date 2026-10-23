@file:OptIn(DelicateMaplibreComposeApi::class, ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.map

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.runFfiComposeUiTest
import org.maplibre.compose.mlnffi.setFfiTestMapContent
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.GroundScaleGlobalState
import org.maplibre.compose.style.GroundScaleTolerance
import org.maplibre.compose.style.groundScale as expectedGroundScale
import org.maplibre.compose.util.DelicateMaplibreComposeApi
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class DesktopGroundScalePresentationTest {
  @Test
  fun a_presented_map_writes_the_ground_scale_of_its_camera_and_rewrites_it_after_a_style_reload() {
    val cache = FfiTestPlatform.createCacheFile()
    try {
      runFfiComposeUiTest {
        val options = MapRuntimeOptions { cacheFile = cache }
        val runtime = createMapRuntime(from = options)
        val state =
          runtime.createMapState(
            BaseStyle.Empty,
            cameraPosition = CameraPosition(center = Position(0.0, 0.0), zoom = 4.0),
          ) {}
        // The map queues the write in the same main turn that publishes a ready style or a new
        // viewport, and runs commands in submission order, so a later read observes it.
        suspend fun assertGroundScale(latitude: Double) {
          val globalState =
            Json.parseToJsonElement(
                state.withPlatformMap { map.getGlobalState() }.await().decodeToString(),
              )
              .jsonObject
          val written =
            assertNotNull(globalState[GroundScaleGlobalState], "No ground scale was written")
              .jsonPrimitive
              .float
          assertTrue(
            abs(written / expectedGroundScale(latitude) - 1f) <= GroundScaleTolerance,
            "Expected the ground scale at latitude $latitude, got $written",
          )
        }
        fun presentsLatitude(latitude: Double): Boolean {
          val center = state.currentMapAttachment?.viewport?.cameraPosition?.center ?: return false
          return abs(center.latitude - latitude) < 1e-6
        }
        try {
          setFfiTestMapContent(options) {
            MaplibreMap(state = state, modifier = Modifier.size(128.dp))
          }
          waitUntil(timeoutMillis = 10_000) {
            state.style.loadState == StyleLoadState.Ready && presentsLatitude(0.0)
          }
          assertGroundScale(0.0)
          runOnIdle {
            state.setCameraPosition(CameraPosition(center = Position(0.0, 60.0), zoom = 4.0))
          }
          waitUntil(timeoutMillis = 10_000) { presentsLatitude(60.0) }
          assertGroundScale(60.0)
          // A new style starts without global state; the map writes the current scale again.
          runOnIdle {
            assertNotNull(state.style.asMutable).baseStyle =
              BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
          }
          waitUntil(timeoutMillis = 10_000) {
            state.style.baseStyle is BaseStyle.Json && state.style.loadState == StyleLoadState.Ready
          }
          assertGroundScale(60.0)
        } finally {
          runtime.close()
          runtime.awaitClosed()
        }
      }
    } finally {
      FfiTestPlatform.deleteCacheFile(cache)
    }
  }
}
