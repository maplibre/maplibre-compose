package org.maplibre.compose.demoapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.UiComposable
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.demos.CastelloPlanDemo
import org.maplibre.compose.demoapp.demos.DataVizDemo
import org.maplibre.compose.demoapp.demos.DragDropDemo
import org.maplibre.compose.demoapp.demos.LiveTrackingDemo
import org.maplibre.compose.demoapp.demos.LocationDemo
import org.maplibre.compose.demoapp.demos.MagnifyingLensDemo
import org.maplibre.compose.demoapp.demos.Manhattan3dDemo
import org.maplibre.compose.demoapp.demos.MapSnapshotterDemo
import org.maplibre.compose.demoapp.demos.MaterialStyleDemo
import org.maplibre.compose.demoapp.demos.TransitNetworkDemo
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * Selecting a demo composes [MapContent] into the shared map and [Overlay] on top of the map. The
 * camera moves to [destination]. Each demo owns its state internally.
 */
interface Demo {
  val name: String
  val description: String
  val destination: DemoDestination

  /**
   * Replaces the user's chosen light style while this demo is selected. See [DemoStyle].
   *
   * Declare this only when the demo genuinely depends on its base style: it reads the style's own
   * data or glyph endpoint, or its data visualization only reads against a deliberately muted
   * canvas. Demos whose content is self-contained should stay agnostic and honor the user's chosen
   * style and theme.
   */
  val preferredLightStyle: DemoStyle?
    get() = null

  /** The preferred dark style. Defaults to the light style for demos with a fixed basemap. */
  val preferredDarkStyle: DemoStyle?
    get() = preferredLightStyle

  /** An optional map pin that restores a useful view of the demo. */
  val pointerPin: DemoPointerPin?
    get() = null

  @MaplibreComposable @Composable fun MapContent() {}

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

/** The camera movement that occurs when a demo is selected or its pointer pin is pressed. */
sealed interface DemoDestination {
  /** Fits a geographic region inside the camera viewport. */
  data class FitBounds(val bounds: BoundingBox) : DemoDestination

  /** Moves to a complete camera position without deriving a zoom level from geographic bounds. */
  data class ExactCamera(val position: CameraPosition) : DemoDestination

  /** Preserves the current camera until the demo moves it from live data or user input. */
  data object None : DemoDestination
}

/** A point shown on the map and the camera movement that its button restores. */
data class DemoPointerPin(val target: Position, val destination: DemoDestination)

internal val BoundingBox.center: Position
  get() {
    val centerLongitude = (west + if (east < west) east + 360.0 else east) / 2
    return Position(
      longitude = if (centerLongitude > 180.0) centerLongitude - 360.0 else centerLongitude,
      latitude = (south + north) / 2,
    )
  }

/** Demos appear in the shell in this order. */
val allDemos: List<Demo> =
  listOf(
    Manhattan3dDemo,
    CastelloPlanDemo,
    DataVizDemo,
    LiveTrackingDemo,
    DragDropDemo,
    MagnifyingLensDemo,
    MapSnapshotterDemo,
    TransitNetworkDemo,
    LocationDemo,
    MaterialStyleDemo,
  )
