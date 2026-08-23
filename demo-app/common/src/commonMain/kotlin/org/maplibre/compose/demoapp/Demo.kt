package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.UiComposable
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.demoapp.demos.CastelloPlanDemo
import org.maplibre.compose.demoapp.demos.DataVizDemo
import org.maplibre.compose.demoapp.demos.DragDropDemo
import org.maplibre.compose.demoapp.demos.LiveTrackingDemo
import org.maplibre.compose.demoapp.demos.LocationDemo
import org.maplibre.compose.demoapp.demos.MagnifyingLensDemo
import org.maplibre.compose.demoapp.demos.Manhattan3dDemo
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * A demo lives at a real place in the world. Selecting it composes [MapContent] into the shared map
 * and [Overlay] on top of the map. When [fliesOnSelect] is true, the camera flies to [region] or
 * [camera]. Each demo owns its state internally.
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

  /** Whether the pointer pin points back to [region]. A worldwide demo turns it off. */
  val showsPointerPin: Boolean
    get() = true

  /**
   * Whether the shell flies to [region] or [camera] when this demo is selected. A demo that drives
   * the camera from live data turns this off.
   */
  val fliesOnSelect: Boolean
    get() = true

  @MaplibreComposable @Composable fun MapContent(cameraState: CameraState) {}

  /**
   * Compose UI drawn over the map while this demo is selected. [state] exposes the shell's
   * settings, style, and camera.
   *
   * [org.maplibre.compose.overlay.MapOverlayScope.placedAt] pins a child to a geographic position.
   */
  @UiComposable @Composable fun MapOverlayScope.Overlay(state: DemoAppState) {}

  /**
   * Controls shown in the sheet or side panel while this demo is selected. [state] exposes the
   * shell's settings, style, and camera.
   */
  @UiComposable @Composable fun Panel(state: DemoAppState) {}
}

val Demo.center: Position
  get() =
    Position(
      longitude = (region.west + region.east) / 2,
      latitude = (region.south + region.north) / 2,
    )

/** Demos that cannot run in the browser; empty on the js target. */
internal expect val extraDemos: List<Demo>

/** Demos appear in the shell in this order. */
val allDemos: List<Demo> =
  listOf(
    Manhattan3dDemo,
    CastelloPlanDemo,
    DataVizDemo,
    LiveTrackingDemo,
    DragDropDemo,
    MagnifyingLensDemo,
  ) + extraDemos + listOf(LocationDemo)
