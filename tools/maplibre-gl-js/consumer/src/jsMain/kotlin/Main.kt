import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.WebMapPresentation
import org.maplibre.compose.map.createMapRuntime
import org.maplibre.compose.style.BaseStyle
import web.dom.document
import web.html.HTMLElement

fun main() {
  MainScope().launch {
    try {
      val runtime = createMapRuntime(MapRuntimeOptions())
      val state =
        runtime.createMapState(
          BaseStyle.Json(
            """{
          "version":8,
          "sources":{"point":{"type":"geojson","data":{
            "type":"FeatureCollection","features":[{"type":"Feature","properties":{},
            "geometry":{"type":"Point","coordinates":[0,0]}}]}}},
          "layers":[{"id":"point","type":"circle","source":"point",
            "paint":{"circle-radius":30,"circle-color":"#ff0000"}}]
        }"""
          ),
          cameraPosition = CameraPosition(zoom = 2.0),
        )
      val host = document.createElement("div").unsafeCast<HTMLElement>()
      host.style.cssText = "width:256px;height:256px"
      document.body.appendChild(host)
      val presentation = WebMapPresentation(state)
      presentation.attachContainer(host)
      withTimeout(20_000) {
        while (state.queryRenderedFeatures(DpOffset(128.dp, 128.dp), setOf("point")).isEmpty()) {
          check(presentation.failure == null) { presentation.failure.toString() }
          delay(16)
        }
      }
      document.body.setAttribute("data-result", "passed")
    } catch (error: Throwable) {
      document.body.setAttribute("data-result", "failed: $error")
      throw error
    }
  }
}
