package org.maplibre.compose.demoapp.demos

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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.demoapp.design.SliderRow
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

private val DragColor = Color(0xFF00695C)

/** Uses Compose overlays to edit geographic points and bounds. */
object DragDropDemo : Demo {
  override val name = "Drag & drop"
  override val description = "Drag a map handle directly. Adjust the padding around small handles."

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

  private enum class Handle {
    Pin,
    Northwest,
    Southeast,
  }

  private var dragPadding by mutableStateOf(12f)

  private val handles: List<Handle>
    get() =
      when (mode) {
        Mode.Pin -> listOf(Handle.Pin)
        Mode.BoundingBox -> listOf(Handle.Northwest, Handle.Southeast)
      }

  private fun position(handle: Handle): Position =
    when (handle) {
      Handle.Pin -> pinPosition
      Handle.Northwest -> northwest
      Handle.Southeast -> southeast
    }

  @Composable
  override fun MapOverlayScope.Overlay(state: DemoAppState) {
    for (handle in handles) key(handle) {
      Box(
        modifier =
          Modifier.placedAt(position(handle)).size((20f + 2f * dragPadding).dp).pointerInput(
            mapState,
            handle,
          ) {
            fun moveBy(amount: Offset) {
              val screen = mapState.screenLocationFromPosition(position(handle)) ?: return
              val moved =
                mapState.positionFromScreenLocation(
                  screen + DpOffset(amount.x.toDp(), amount.y.toDp())
                ) ?: return
              when (handle) {
                Handle.Pin -> pinPosition = moved
                Handle.Northwest -> northwest = moved
                Handle.Southeast -> southeast = moved
              }
            }

            detectDragGestures(
              orientationLock = null,
              onDragStart = { down, change, overSlop ->
                // Include the distance to recognition; onDrag supplies only the overshoot.
                moveBy(change.position - down.position - overSlop)
              },
              onDrag = { change, amount ->
                change.consume()
                moveBy(amount)
              },
            )
          },
        contentAlignment = Alignment.Center,
      ) {
        Box(
          Modifier.size(20.dp)
            .background(DragColor, CircleShape)
            .border(2.dp, Color.White, CircleShape)
        )
      }
    }
  }

  /** Keeps the box valid when one handle crosses the other. */
  private fun boundingBox(): BoundingBox {
    val first = position(Handle.Northwest)
    val second = position(Handle.Southeast)
    return BoundingBox(
      west = minOf(first.longitude, second.longitude),
      south = minOf(first.latitude, second.latitude),
      east = maxOf(first.longitude, second.longitude),
      north = maxOf(first.latitude, second.latitude),
    )
  }

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
    if (mode == Mode.BoundingBox) {
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
    SliderRow("Drag padding", dragPadding, 0f..24f, { "${it.roundToInt()} dp" }) {
      dragPadding = it
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
      Text(
        "Drag any handle to change its position.",
        style = MaterialTheme.typography.bodyMedium,
      )
      when (mode) {
        Mode.Pin -> {
          val pinPosition = position(Handle.Pin)
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

private fun Double.format(decimals: Int): String {
  var factor = 1.0
  repeat(decimals) { factor *= 10 }
  return ((this * factor).roundToInt() / factor).toString()
}
