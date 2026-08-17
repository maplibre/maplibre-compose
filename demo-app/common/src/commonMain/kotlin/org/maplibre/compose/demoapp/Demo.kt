package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.UiComposable
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.demos.CastelloPlanDemo
import org.maplibre.compose.demoapp.demos.DataVizDemo
import org.maplibre.compose.demoapp.demos.Manhattan3dDemo
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * A demo lives at a real place in the world. Selecting it flies the camera to [region] and composes
 * [MapContent] into the shared map. Each demo owns its state internally.
 */
interface Demo {
  val name: String
  val description: String
  val region: BoundingBox

  /** Applied once when the demo is selected; a later choice by the user wins. */
  val preferredStyle: DemoStyle?
    get() = null

  /**
   * The exact camera the flight ends at. Null fits [region] to the viewport instead; set this when
   * the demo needs a composed view, such as a pitched skyline.
   */
  val camera: CameraPosition?
    get() = null

  @MaplibreComposable @Composable fun MapContent() {}

  /** Controls shown in the sheet or side panel while this demo is selected. */
  @UiComposable @Composable fun Panel() {}
}

val Demo.center: Position
  get() =
    Position(
      longitude = (region.west + region.east) / 2,
      latitude = (region.south + region.north) / 2,
    )

/** Demos appear in the shell in this order. */
val allDemos: List<Demo> = listOf(Manhattan3dDemo, CastelloPlanDemo, DataVizDemo)
