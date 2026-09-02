package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import org.maplibre.compose.demoapp.design.FpsCapRow
import org.maplibre.compose.demoapp.design.SectionHeader
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.demoapp.design.SwitchRow
import org.maplibre.compose.map.CameraProjection

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
        options.copy(
          cameraProjection =
            if (axonometric) CameraProjection.Axonometric() else CameraProjection.Perspective
        )
    },
  )

  SectionHeader("Renderer")
  RenderModeItem(settings)
  FpsCapRow(options.maximumFps) { settings.renderOptions = options.copy(maximumFps = it) }

  SectionHeader("Debug views")
  SwitchRow("Tile borders", options.isTileBordersEnabled) {
    settings.renderOptions = options.copy(isTileBordersEnabled = it)
  }
  SwitchRow("Tile timestamps", options.isTileTimestampsEnabled) {
    settings.renderOptions = options.copy(isTileTimestampsEnabled = it)
  }
  SwitchRow("Tile parse status", options.isTileParseStatusEnabled) {
    settings.renderOptions = options.copy(isTileParseStatusEnabled = it)
  }
  SwitchRow("Collision boxes", options.isCollisionBoxesEnabled) {
    settings.renderOptions = options.copy(isCollisionBoxesEnabled = it)
  }
}
