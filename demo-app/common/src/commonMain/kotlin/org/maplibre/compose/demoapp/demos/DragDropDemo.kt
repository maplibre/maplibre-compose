package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

private val DragColor = Color(0xFF00695C)

/**
 * Drags overlay children placed on the map: a location-picker pin, or the two corner handles of a
 * bounding box. The drag accumulates pointer deltas on the screen offset captured when the drag
 * started, which keeps the child from jittering under the pointer as it follows the position.
 */
object DragDropDemo : Demo {
  override val name = "Drag & drop"
  override val description = "Drag a location-picker pin or the corner handles of a bounding box."

  // A neighborhood view gives both modes room to drag in without a detour through the camera.
  override val destination =
    DemoDestination.FitBounds(
      BoundingBox(west = -122.3452, south = 47.6155, east = -122.3252, north = 47.6255)
    )

  private enum class Mode(val label: String) {
    Pin("Pin"),
    BoundingBox("Bounding box"),
  }

  private var mode by mutableStateOf(Mode.Pin)
  private var pinPosition by mutableStateOf(Position(longitude = -122.3352, latitude = 47.6205))
  private var northwest by mutableStateOf(Position(longitude = -122.3377, latitude = 47.6225))
  private var southeast by mutableStateOf(Position(longitude = -122.3327, latitude = 47.6185))

  /**
   * Moves the overlay child with the pointer. The screen offset of [position] is captured on drag
   * start and pointer deltas are accumulated onto it, so the child never has to be read back while
   * it moves under the pointer. Pointer events report pixels, so the anchor captured from
   * [MapState.screenLocationFromPosition] is scaled up before the px-based projection receives the
   * sum.
   */
  private fun Modifier.draggablePosition(
    mapState: MapState,
    position: () -> Position,
    onDrag: (Position) -> Unit,
  ): Modifier =
    pointerInput(mapState) {
      var start: Offset? = null
      var accumulated = Offset.Zero
      detectDragGestures(
        onDragStart = {
          start =
            mapState.screenLocationFromPosition(position())?.let {
              Offset(it.x.toPx(), it.y.toPx())
            }
          accumulated = Offset.Zero
        },
        onDrag = onDrag@{ change, dragAmount ->
            change.consume()
            val startOffset = start ?: return@onDrag
            accumulated += dragAmount
            val screen = startOffset + accumulated
            mapState
              .positionFromScreenLocation(DpOffset(screen.x.toDp(), screen.y.toDp()))
              ?.let(onDrag)
          },
      )
    }

  /** The box the two handles span, normalized so it stays valid when a handle crosses the other. */
  private fun boundingBox() =
    BoundingBox(
      west = minOf(northwest.longitude, southeast.longitude),
      south = minOf(northwest.latitude, southeast.latitude),
      east = maxOf(northwest.longitude, southeast.longitude),
      north = maxOf(northwest.latitude, southeast.latitude),
    )

  private fun BoundingBox.toPolygon() =
    Polygon(
      listOf(
        listOf(
          Position(longitude = west, latitude = north),
          Position(longitude = east, latitude = north),
          Position(longitude = east, latitude = south),
          Position(longitude = west, latitude = south),
          Position(longitude = west, latitude = north),
        )
      )
    )

  @Composable
  override fun MapContent() {
    if (mode != Mode.BoundingBox) return
    val source =
      rememberGeoJsonSource(
        GeoJsonData.Features(Feature(geometry = boundingBox().toPolygon(), properties = null))
      )
    FillLayer(
      id = "drag-drop-box",
      source = source,
      color = const(DragColor),
      opacity = const(0.2f),
    )
    LineLayer(
      id = "drag-drop-box-outline",
      source = source,
      color = const(DragColor),
      width = const(2.dp),
    )
  }

  @Composable
  override fun MapOverlayScope.Overlay(state: DemoAppState) {
    when (mode) {
      Mode.Pin ->
        Pin(
          Modifier.placedAt(pinPosition, Alignment.BottomCenter).draggablePosition(
            mapState,
            { pinPosition },
          ) {
            pinPosition = it
          }
        )
      Mode.BoundingBox -> {
        Handle(
          Modifier.placedAt(northwest).draggablePosition(mapState, { northwest }) {
            northwest = it
          }
        )
        Handle(
          Modifier.placedAt(southeast).draggablePosition(mapState, { southeast }) {
            southeast = it
          }
        )
      }
    }
  }

  @Composable
  override fun Panel(state: DemoAppState) {
    SegmentedRow(
      label = "Drag",
      options = Mode.entries,
      selected = mode,
      optionLabel = { it.label },
      onSelect = { mode = it },
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
      when (mode) {
        Mode.Pin -> {
          Text("Pin location", style = MaterialTheme.typography.bodyLarge)
          Text(
            "lat ${pinPosition.latitude.format(5)}, lng ${pinPosition.longitude.format(5)}",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        Mode.BoundingBox -> {
          val box = boundingBox()
          Text("Bounding box", style = MaterialTheme.typography.bodyLarge)
          Text(
            "north ${box.north.format(5)}, south ${box.south.format(5)}",
            style = MaterialTheme.typography.bodyMedium,
          )
          Text(
            "west ${box.west.format(5)}, east ${box.east.format(5)}",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }
  }
}

/**
 * A teardrop pin: a round head over a tip. Placed at [Alignment.BottomCenter], the tip is the
 * point.
 */
@Composable
private fun Pin(modifier: Modifier = Modifier) {
  Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      Modifier.size(24.dp).background(DragColor, CircleShape).border(2.dp, Color.White, CircleShape)
    )
    Canvas(Modifier.size(width = 12.dp, height = 7.dp)) {
      val tip = Path()
      tip.moveTo(0f, 0f)
      tip.lineTo(size.width, 0f)
      tip.lineTo(size.width / 2, size.height)
      tip.close()
      drawPath(tip, DragColor)
    }
  }
}

@Composable
private fun Handle(modifier: Modifier = Modifier) {
  Box(
    modifier.size(20.dp).background(DragColor, CircleShape).border(2.dp, Color.White, CircleShape)
  )
}

private fun Double.format(decimals: Int): String {
  var factor = 1.0
  repeat(decimals) { factor *= 10 }
  return ((this * factor).roundToInt() / factor).toString()
}
