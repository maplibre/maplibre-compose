package org.maplibre.compose.map

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import kotlin.test.Test
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
import org.maplibre.compose.style.roundedGroundScale
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class DesktopGroundScalePresentationTest {
  @Test
  fun a_presented_map_supplies_the_ground_scale_of_its_camera_latitude() {
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
          waitUntil(timeoutMillis = 10_000) { groundScale() == roundedGroundScale(0.0) }
          runOnIdle {
            state.setCameraPosition(CameraPosition(center = Position(0.0, 60.0), zoom = 4.0))
          }
          waitUntil(timeoutMillis = 10_000) { groundScale() == roundedGroundScale(60.0) }
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
