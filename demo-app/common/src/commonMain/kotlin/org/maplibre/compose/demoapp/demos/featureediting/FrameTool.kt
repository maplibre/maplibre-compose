package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import org.maplibre.compose.editing.EditorEvent
import org.maplibre.compose.editing.EditorFeature
import org.maplibre.compose.editing.EditorHandle
import org.maplibre.compose.editing.EditorTool
import org.maplibre.compose.editing.FeatureEditorState
import org.maplibre.compose.editing.HandleHit
import org.maplibre.compose.editing.HandleKind
import org.maplibre.compose.editing.SelectTool
import org.maplibre.compose.editing.contains
import org.maplibre.compose.editing.handleTarget
import org.maplibre.compose.editing.mapPositions
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.turf.measurement.area
import org.maplibre.spatialk.turf.measurement.bearingTo
import org.maplibre.spatialk.turf.measurement.center
import org.maplibre.spatialk.turf.measurement.computeBbox
import org.maplibre.spatialk.turf.measurement.distance
import org.maplibre.spatialk.turf.measurement.length
import org.maplibre.spatialk.turf.measurement.offset
import org.maplibre.spatialk.turf.measurement.toPolygon
import org.maplibre.spatialk.turf.misc.nearestPointTo
import org.maplibre.spatialk.turf.transformation.circle
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.DMS.Degrees
import org.maplibre.spatialk.units.International
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.meters

/** Handle kinds that the frame around a selected shape adds to the editor's vertex handles. */
internal sealed interface FrameHandle : HandleKind {
  /** The source property value the handle layers match on. */
  val key: String

  data object Move : FrameHandle {
    override val key = "move"
  }

  data object Rotate : FrameHandle {
    override val key = "rotate"
  }

  data object Scale : FrameHandle {
    override val key = "scale"
  }

  data object Radius : FrameHandle {
    override val key = "radius"
  }

  data object Station : FrameHandle {
    override val key = "station"
  }
}

/** The bounding box of a shape padded in screen space, and its outline. */
internal class Frame(val padded: BoundingBox) {
  val outline: Polygon
    get() = padded.toPolygon()
}

internal val FramePadding = 28.dp

/** How close to the station dot a midpoint handle is dropped. */
internal val StationClearance = 12.dp

internal fun frameOf(geometry: Geometry, padMeters: Double): Frame {
  val bbox = geometry.computeBbox()
  val padLat = padMeters / METERS_PER_DEGREE
  val centerLat = (bbox.south + bbox.north) / 2
  val padLon = padLat / cos(centerLat * PI / 180)
  val padded =
    BoundingBox(
      bbox.west - padLon,
      (bbox.south - padLat).coerceAtLeast(-90.0),
      bbox.east + padLon,
      (bbox.north + padLat).coerceAtMost(90.0),
    )
  return Frame(padded)
}

/**
 * Adds move, rotate, scale, radius and station handles around a single selected shape and turns
 * drags on them into one undo step each. Everything else goes to [inner].
 */
internal class FrameTool(private val demo: FeatureEditingState, private val inner: SelectTool) :
  EditorTool by inner {

  override fun handles(state: FeatureEditorState): List<EditorHandle> {
    val base = inner.handles(state)
    val gesture = demo.frameGesture
    if (gesture != null) {
      val position =
        if (gesture.kind == FrameHandle.Station) {
          val line = state.feature(gesture.id)?.geometry as? LineString
          line?.let { stationPoint(it, demo.stationFraction) } ?: gesture.handle
        } else gesture.handle
      return base + EditorHandle(gesture.kind, null, position)
    }
    if (state.draft != null) return base
    val id = state.selection.singleOrNull() ?: return base
    val feature = state.feature(id) ?: return base
    val frame = frameHandles(feature, state.visibleBounds)
    // The station dot sits on the line, over the midpoint handle of a two-point line: a press there
    // must slide the station, not insert a vertex.
    val station = frame.firstOrNull { it.kind == FrameHandle.Station }
    if (station == null) return base + frame
    val clearance = (StationClearance.value * demo.metersPerDp).meters
    return base.filterNot {
      it.kind == HandleKind.Midpoint && distance(it.position, station.position) < clearance
    } + frame
  }

  private fun frameHandles(feature: EditorFeature, bounds: BoundingBox?): List<EditorHandle> {
    val geometry = feature.geometry
    val frame = frameOf(geometry, FramePadding.value * demo.metersPerDp)
    val padded = frame.padded
    val handles = ArrayList<EditorHandle>(4)
    fun add(kind: FrameHandle, position: Position) {
      if (bounds == null || bounds.contains(position)) {
        handles += EditorHandle(kind, null, position)
      }
    }
    add(FrameHandle.Move, Position(padded.west, padded.north))
    when (feature.kind) {
      ShapeKind.Circle -> {
        val center = circleCenter(geometry)
        add(FrameHandle.Radius, center.offset(circleRadius(geometry, center), Bearing.East))
      }
      ShapeKind.Polygon,
      ShapeKind.Line -> {
        add(FrameHandle.Rotate, Position(padded.east, padded.north))
        add(FrameHandle.Scale, Position(padded.east, padded.south))
        if (geometry is LineString) {
          add(FrameHandle.Station, stationPoint(geometry, demo.stationFraction))
        }
      }
    }
    return handles
  }

  override fun onEvent(event: EditorEvent, state: FeatureEditorState): Boolean {
    val gesture = demo.frameGesture
    if (gesture == null) {
      if (event is EditorEvent.Press) {
        val handle = (event.hit as? HandleHit)?.handle
        val kind = handle?.kind
        if (kind is FrameHandle) {
          // Secondary and middle buttons keep their map gestures.
          if (event.pointer.buttons.any { it != PointerButton.Primary }) return false
          val id = state.selection.singleOrNull() ?: return false
          val before = state.featureBefore(event.step, id) ?: return false
          demo.frameGesture =
            FrameGesture(
              kind = kind,
              step = event.step,
              id = id,
              center = before.geometry.computeBbox().center().coordinates,
              origin = event.pointer,
              pointer = event.pointer,
              handleOrigin = handle.position,
              handle = handle.position,
              readout = "",
            )
          return true
        }
      }
      return inner.onEvent(event, state)
    }
    return when (event) {
      is EditorEvent.Drag -> {
        onDrag(gesture, event, state)
        false
      }
      is EditorEvent.Release -> {
        demo.frameGesture = null
        false
      }
      is EditorEvent.LongPress -> false
      is EditorEvent.Tap -> {
        demo.frameGesture = null
        true
      }
      is EditorEvent.Cancel -> {
        state.revert(gesture.step)
        demo.frameGesture = null
        false
      }
      else -> inner.onEvent(event, state)
    }
  }

  private fun onDrag(gesture: FrameGesture, event: EditorEvent.Drag, state: FeatureEditorState) {
    val before = state.featureBefore(event.step, gesture.id) ?: return
    val center = gesture.center
    val origin = gesture.handleOrigin
    val p = event.handleTarget ?: return
    val shift = KeyModifier.Shift in event.pointer.modifierKeys
    val units = demo.units
    val geometry: Geometry
    val readout: String
    when (gesture.kind) {
      FrameHandle.Move -> {
        val d = distance(origin, p)
        val b = origin.bearingTo(p)
        geometry = before.geometry.mapPositions { it.offset(d, b) }
        readout = "${units.length(d)} · ${units.bearing(b)}"
      }
      FrameHandle.Rotate -> {
        var rotation = center.bearingTo(origin).smallestRotationTo(center.bearingTo(p))
        if (shift) {
          rotation = ((rotation.toDouble(Degrees) / 15).roundToInt() * 15).degrees
        }
        geometry =
          before.geometry.mapPositions { v ->
            center.offset(distance(center, v), center.bearingTo(v) + rotation)
          }
        val degrees = rotation.toDouble(Degrees).roundToInt()
        readout = if (degrees < 0) "−${-degrees}°" else "+$degrees°"
      }
      FrameHandle.Scale -> {
        val reference = distance(center, origin)
        var factor =
          if (reference.isZero) 1.0 else (distance(center, p) / reference).coerceIn(0.05, 20.0)
        if (shift) factor = ((factor / 0.25).roundToInt() * 0.25).coerceAtLeast(0.25)
        geometry =
          before.geometry.mapPositions { v ->
            center.offset(distance(center, v) * factor, center.bearingTo(v))
          }
        val measure =
          if (before.kind == ShapeKind.Line) units.length(geometry.length())
          else units.area(geometry.area())
        readout = "×${formatNumber(factor, 2)} · $measure"
      }
      FrameHandle.Radius -> {
        var radius = maxOf(distance(center, p), 1.meters)
        if (shift) {
          radius =
            ((radius.toDouble(International.Meters) / 10).roundToInt() * 10)
              .coerceAtLeast(10)
              .meters
        }
        geometry = circle(center, radius, steps = CIRCLE_STEPS)
        readout = "r ${units.length(radius)}"
      }
      FrameHandle.Station -> {
        val line = before.geometry as? LineString ?: return
        val nearest = line.nearestPointTo(p)
        val length = line.length()
        if (length.isPositive) {
          demo.stationFraction = (nearest.properties.location / length).coerceIn(0.0, 1.0)
        }
        demo.frameGesture = gesture.copy(pointer = event.pointer, handle = p)
        return
      }
    }
    if (
      state.update(listOf(before.copy(geometry = geometry, bbox = null)), undoStep = event.step) !=
        null
    ) {
      demo.frameGesture = gesture.copy(pointer = event.pointer, handle = p, readout = readout)
    } else {
      demo.frameGesture = gesture.copy(pointer = event.pointer, handle = p)
    }
  }

  override fun cursor(state: FeatureEditorState): PointerIcon {
    val hover = state.hover
    if (demo.frameGesture != null || (hover is HandleHit && hover.handle.kind is FrameHandle)) {
      return PointerIcon.Hand
    }
    return inner.cursor(state)
  }
}

internal const val CIRCLE_STEPS = 64
