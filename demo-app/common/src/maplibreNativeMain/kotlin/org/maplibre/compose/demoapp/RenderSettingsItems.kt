package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.design.FpsCapRow
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.map.CameraProjection
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.map.cameraProjection
import org.maplibre.compose.map.tileParseStatus
import org.maplibre.compose.map.tileTimestamps

@Composable
actual fun RenderSettingsItems(settings: DemoSettings) {
  val options = settings.renderOptions
  SectionHeader("Camera projection")
  SegmentedRow(
    options = listOf(false, true),
    selected = options.cameraProjection is CameraProjection.Axonometric,
    optionLabel = { if (it) "Axonometric" else "Perspective" },
    onSelect = { axonometric ->
      settings.renderOptions =
        RenderOptions(options) {
          cameraProjection =
            if (axonometric) CameraProjection.Axonometric() else CameraProjection.Perspective
        }
    },
  )

  SectionHeader("Renderer")
  RenderModeItem(settings)
  FpsCapRow(options.maximumFps) { fps ->
    settings.renderOptions = RenderOptions(options) { maximumFps = fps }
  }

  SectionHeader("Debug views")
  SwitchRow("Tile borders", options.debug.tileBorders) { on ->
    settings.renderOptions = RenderOptions(options) { debug { tileBorders = on } }
  }
  SwitchRow("Tile timestamps", options.debug.tileTimestamps) { on ->
    settings.renderOptions = RenderOptions(options) { debug { tileTimestamps = on } }
  }
  SwitchRow("Tile parse status", options.debug.tileParseStatus) { on ->
    settings.renderOptions = RenderOptions(options) { debug { tileParseStatus = on } }
  }
  SwitchRow("Collision boxes", options.debug.collisionBoxes) { on ->
    settings.renderOptions = RenderOptions(options) { debug { collisionBoxes = on } }
  }
}
