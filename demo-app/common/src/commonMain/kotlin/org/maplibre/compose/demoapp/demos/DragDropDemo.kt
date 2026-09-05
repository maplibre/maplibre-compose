package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.design.SegmentedRow
import org.maplibre.compose.demoapp.design.SliderRow
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.DragAction
import org.maplibre.compose.map.DragEvent
import org.maplibre.compose.map.MapGestures
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.ModifierFilter
import org.maplibre.compose.map.PointerFilter
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

private val DragColor = Color(0xFF00695C)

/** Selects rendered handles and previews a custom drag until release commits its position. */
object DragDropDemo : Demo {
  override val name = "Drag & drop"
  override val description =
    "Select and drag a map handle. Adjust the padding around small click targets."

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

  private var selectedHandle by mutableStateOf(Handle.Pin)
  private var hitPadding by mutableStateOf(12f)
  private var dragPreview by mutableStateOf<DragPreview?>(null)

  private data class DragPreview(
    val handle: Handle,
    val origin: DpOffset,
    val displacement: DpOffset,
    val position: Position,
  )

  private fun position(handle: Handle): Position =
    dragPreview?.takeIf { it.handle == handle }?.position
      ?: when (handle) {
        Handle.Pin -> pinPosition
        Handle.Northwest -> northwest
        Handle.Southeast -> southeast
      }

  override fun gestures(base: MapGestures, mapState: MapState): MapGestures =
    MapGestures(from = base) {
      drag(
        id = "selected-handle-${mode.name}",
        filter = PointerFilter(modifiers = ModifierFilter.Exactly()),
      ) {
        canStart { press ->
          val screen = mapState.screenLocationFromPosition(position(selectedHandle))
          screen != null &&
            hypot(
              (press.screenOffset.x - screen.x).value,
              (press.screenOffset.y - screen.y).value,
            ) <= 10f + hitPadding
        }
        action = DragAction.Custom
        onEvent { event ->
          when (event) {
            is DragEvent.Start -> {
              val handle = selectedHandle
              val position = position(handle)
              dragPreview =
                mapState.screenLocationFromPosition(position)?.let {
                  DragPreview(handle, it, DpOffset.Zero, position)
                }
            }
            is DragEvent.Delta ->
              dragPreview?.let { preview ->
                val displacement = preview.displacement + event.delta
                val position = mapState.positionFromScreenLocation(preview.origin + displacement)
                dragPreview =
                  preview.copy(
                    displacement = displacement,
                    position = position ?: preview.position,
                  )
              }
            is DragEvent.End -> {
              dragPreview?.let { preview ->
                when (preview.handle) {
                  Handle.Pin -> pinPosition = preview.position
                  Handle.Northwest -> northwest = preview.position
                  Handle.Southeast -> southeast = preview.position
                }
              }
              dragPreview = null
            }
            is DragEvent.Cancel -> dragPreview = null
          }
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
    val handles =
      when (mode) {
        Mode.Pin -> listOf(Handle.Pin)
        Mode.BoundingBox -> listOf(Handle.Northwest, Handle.Southeast)
      }
    for (handle in handles) key(handle) {
      val source =
        rememberGeoJsonSource(
          GeoJsonData.Features(Feature(geometry = Point(position(handle)), properties = null))
        )
      CircleLayer(
        id = "drag-drop-${handle.name}",
        source = source,
        radius = const(if (selectedHandle == handle) 8.dp else 6.dp),
        color = const(if (selectedHandle == handle) DragColor else Color(0xFFF9A825)),
        strokeWidth = const(2.dp),
        strokeColor = const(Color.White),
        hitPadding = hitPadding.dp,
        onClick = {
          selectedHandle = handle
          ClickResult.Consume
        },
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
      onSelect = {
        mode = it
        selectedHandle = if (it == Mode.Pin) Handle.Pin else Handle.Northwest
      },
    )
    SliderRow("Hit padding", hitPadding, 0f..24f, { "${it.roundToInt()} dp" }) {
      hitPadding = it
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
      Text(
        "Tap a handle to select it, then drag the green handle. Release to save; adding a second finger cancels the edit.",
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
