package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.demoapp.DefaultMapControls
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoMapControls
import org.maplibre.compose.demoapp.DemoPointerPin
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.demoapp.RenderModeRow
import org.maplibre.compose.demoapp.design.FpsCapRow
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.CirclePitchScale
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/**
 * Red discs drawn by MapLibre, each covered by a cyan Compose disc of the same size. While the two
 * stay in sync, no red shows; the red crescent that appears points from the cyan disc toward where
 * the map drew.
 *
 * In [Mode.OverlayFollowsMap] the cyan discs are placed at the red discs' positions, so red shows
 * when the overlay lags the map. In [Mode.MapFollowsOverlay] a drag moves the cyan discs directly
 * and pans the camera by the same distance, so red shows when the map lags behind its camera
 * commands.
 */
object OverlayAlignmentDemo : Demo {
  override val name = "Overlay alignment"
  override val description =
    "Compose discs over identical map discs. Move the map, or drag the discs and let the map follow. Any red that shows is the two out of sync."

  private enum class Mode(val label: String) {
    OverlayFollowsMap("Overlay follows map"),
    MapFollowsOverlay("Map follows overlay"),
  }

  private var mode by mutableStateOf(Mode.OverlayFollowsMap)

  /** In [Mode.MapFollowsOverlay], where the drag started and how far the finger has moved. */
  private class Drag(val viewport: Viewport, val target: DpOffset, val anchors: List<DpOffset>) {
    var delta by mutableStateOf(DpOffset.Zero)
  }

  private var drag by mutableStateOf<Drag?>(null)

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
        GeoJsonData.Features(FeatureCollection(positions.map { Feature(Point(it), null) })),
      )
    CircleLayer(
      id = "overlay-alignment-discs",
      source = source,
      color = const(Color(0xFFFF3B30)),
      radius = const(discDiameter / 2),
      pitchScale = const(CirclePitchScale.Viewport),
    )
  }

  override fun interactions(mapState: MapState, settings: MapInteractions): MapInteractions =
    if (mode == Mode.MapFollowsOverlay) MapInteractions.None else settings

  @Composable
  override fun MapOverlayScope.Overlay(state: DemoAppState, controls: DemoMapControls) {
    when (mode) {
      Mode.OverlayFollowsMap -> {
        positions.forEach { position ->
          key(position) { Box(Modifier.placedAt(position).then(discModifier)) }
        }
      }
      Mode.MapFollowsOverlay -> FollowingDiscs(state.mapState)
    }
    DefaultMapControls(controls)
  }

  /**
   * Discs that move with the finger from Compose state alone. Each drag event also pans the camera
   * by the same distance, so the map discs keep up only as fast as camera commands render.
   */
  @Composable
  private fun MapOverlayScope.FollowingDiscs(mapState: MapState) {
    val density = LocalDensity.current
    Box(
      Modifier.matchParentSize().pointerInput(mapState) {
        detectDragGestures(
          onDragStart = start@{
              // Under pitch a pan moves points by different screen distances, so the discs could
              // not simply follow the finger. Drags start once the camera is flat.
              if (mapState.cameraPosition.pitch != 0.0) {
                mapState.setCameraPosition(mapState.cameraPosition.copy(pitch = 0.0))
                return@start
              }
              val viewport = mapState.viewport ?: return@start
              val target =
                viewport.screenLocationFromPosition(viewport.cameraPosition.center) ?: return@start
              drag =
                Drag(viewport, target, positions.mapNotNull(viewport::screenLocationFromPosition))
            },
          onDragEnd = { drag = null },
          onDragCancel = { drag = null },
        ) { change, amount ->
          change.consume()
          val active = drag ?: return@detectDragGestures
          active.delta =
            with(density) {
              DpOffset(active.delta.x + amount.x.toDp(), active.delta.y + amount.y.toDp())
            }
          // Whatever the drag-start frame drew at (target - delta) becomes the camera target, so
          // the map content under the finger moves with it.
          val center =
            active.viewport.positionFromScreenLocation(
              DpOffset(active.target.x - active.delta.x, active.target.y - active.delta.y),
            ) ?: return@detectDragGestures
          mapState.setCameraPosition(active.viewport.cameraPosition.copy(center = center))
        }
      },
    )
    val current = drag
    if (current == null) {
      positions.forEach { position ->
        key(position) { Box(Modifier.placedAt(position).then(discModifier)) }
      }
    } else {
      current.anchors.forEach { anchor ->
        // Anchors are measured from the left edge, so neither placement mirrors in RTL.
        Box(
          Modifier.align(AbsoluteAlignment.TopLeft)
            .absoluteOffset {
              val radius = discDiameter / 2
              with(density) {
                IntOffset(
                  (anchor.x + current.delta.x - radius).toPx().roundToInt(),
                  (anchor.y + current.delta.y - radius).toPx().roundToInt(),
                )
              }
            }
            .then(discModifier),
        )
      }
    }
  }

  private val discModifier = Modifier.size(discDiameter).background(Color(0xFF00E5FF), CircleShape)

  @Composable
  override fun PeekPanel(state: DemoAppState) {
    SegmentedRow(
      options = Mode.entries,
      selected = mode,
      optionLabel = { it.label },
      onSelect = {
        drag = null
        mode = it
      },
    )
  }

  @Composable
  override fun Panel(state: DemoAppState) {
    val settings = state.settings
    RenderModeRow(settings)
    FpsCapRow(settings.renderOptions.maximumFps) { fps ->
      settings.renderOptions = RenderOptions(settings.renderOptions) { maximumFps = fps }
    }
  }
}
