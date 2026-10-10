package org.maplibre.compose.map

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
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
        fun groundScale(): Float? =
          (state.currentMapAttachment?.adapter as? MlnFfiMapSession)
            ?.readMap { it.getGlobalState().decodeToString() }
            ?.let { Json.parseToJsonElement(it).jsonObject[GroundScaleGlobalState] }
            ?.jsonPrimitive
            ?.float
        try {
          setFfiTestMapContent(options) {
            MaplibreMap(state = state, modifier = Modifier.size(128.dp))
          }
          fun holds(latitude: Double): Boolean {
            val written = groundScale() ?: return false
            return abs(written / expectedGroundScale(latitude) - 1f) <= GroundScaleTolerance
          }
          waitUntil(timeoutMillis = 10_000) { holds(0.0) }
          runOnIdle {
            state.setCameraPosition(CameraPosition(center = Position(0.0, 60.0), zoom = 4.0))
          }
          waitUntil(timeoutMillis = 10_000) { holds(60.0) }
          // A new style starts without global state; the map writes the current scale again.
          runOnIdle {
            assertNotNull(state.style.asMutable).baseStyle =
              BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
          }
          waitUntil(timeoutMillis = 10_000) {
            state.style.baseStyle is BaseStyle.Json &&
              state.style.loadState == StyleLoadState.Ready &&
              holds(60.0)
          }
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
