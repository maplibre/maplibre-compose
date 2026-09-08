package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.design.FpsCapRow
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.map.overdrawInspector
import org.maplibre.compose.map.padding

@Composable
actual fun RenderSettingsItems(settings: DemoSettings) {
  val options = settings.renderOptions
  FpsCapRow(options.maximumFps) { fps ->
    settings.renderOptions = RenderOptions(options) { maximumFps = fps }
  }
  SwitchRow("Tile borders", options.debug.tileBorders) { on ->
    settings.renderOptions = RenderOptions(options) { debug { tileBorders = on } }
  }
  SwitchRow("Collision boxes", options.debug.collisionBoxes) { on ->
    settings.renderOptions = RenderOptions(options) { debug { collisionBoxes = on } }
  }
  SwitchRow("Camera padding", options.debug.padding) { on ->
    settings.renderOptions = RenderOptions(options) { debug { padding = on } }
  }
  SwitchRow("Overdraw inspector", options.debug.overdrawInspector) { on ->
    settings.renderOptions = RenderOptions(options) { debug { overdrawInspector = on } }
  }
}
