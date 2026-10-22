package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.DefaultMapControls
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoMapControls
import org.maplibre.compose.demoapp.DemoPointerPin
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.demoapp.RenderModeRow
import org.maplibre.compose.demoapp.design.FpsCapRow
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.CirclePitchScale
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/**
 * Red discs drawn by MapLibre, each covered by a cyan Compose disc of the same size placed at the
 * same position. While the overlay keeps up with the rendered map, no red shows; the red crescent
 * that appears points from the overlay toward where the map drew.
 */
object OverlayAlignmentDemo : Demo {
  override val name = "Overlay alignment"
  override val description =
    "Compose discs placed over identical map discs. Pan, zoom, and rotate the map. Any red that shows is the overlay out of sync with the map."

  private val center = Position(longitude = -122.4214, latitude = 37.7749)
  override val destination = DemoDestination.ExactCamera(CameraPosition(center, zoom = 15.0))
  override val pointerPin = DemoPointerPin(center, destination)

  private val discDiameter = 24.dp

  // About 120 dp apart at zoom 15.
  private val positions =
    (-6..6).flatMap { row ->
      (-6..6).map { column ->
        Position(center.longitude + column * 0.0026, center.latitude + row * 0.002)
      }
    }

  @Composable
  override fun MapContent(style: DemoStyle) {
    val source =
      rememberGeoJsonSource(
        GeoJsonData.Features(FeatureCollection(positions.map { Feature(Point(it), null) }))
      )
    CircleLayer(
      id = "overlay-alignment-discs",
      source = source,
      color = const(Color(0xFFFF3B30)),
      radius = const(discDiameter / 2),
      pitchScale = const(CirclePitchScale.Viewport),
    )
  }

  @Composable
  override fun MapOverlayScope.Overlay(state: DemoAppState, controls: DemoMapControls) {
    positions.forEach { position ->
      key(position) {
        Box(
          Modifier.placedAt(position).size(discDiameter).background(Color(0xFF00E5FF), CircleShape)
        )
      }
    }
    DefaultMapControls(controls)
  }

  @Composable
  override fun PeekPanel(state: DemoAppState) {
    RenderModeRow(state.settings)
  }

  @Composable
  override fun Panel(state: DemoAppState) {
    val settings = state.settings
    FpsCapRow(settings.renderOptions.maximumFps) { fps ->
      settings.renderOptions = RenderOptions(settings.renderOptions) { maximumFps = fps }
    }
  }
}
