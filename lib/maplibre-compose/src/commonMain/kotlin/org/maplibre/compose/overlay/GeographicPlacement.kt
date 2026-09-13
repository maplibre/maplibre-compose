package org.maplibre.compose.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.roundToInt
import org.maplibre.compose.map.MapState
import org.maplibre.spatialk.geojson.Position

/** Placement last computed by [MapOverlayScope.placedTowards]. */
@Stable
public class PlacedTowardsState {
  /** Direction in degrees clockwise from screen-up. Zero until the first placement. */
  public var angleDegrees: Float by mutableFloatStateOf(0f)
    internal set

  /** Whether the content is currently placed. */
  public var isPlaced: Boolean by mutableStateOf(false)
    internal set
}

/** Remembers a [PlacedTowardsState] for [MapOverlayScope.placedTowards]. */
@Composable
public fun rememberPlacedTowardsState(): PlacedTowardsState = remember { PlacedTowardsState() }

internal data class GeographicPlacement(
  val mapState: MapState,
  val mapCoordinates: State<LayoutCoordinates?>,
  val position: Position,
  val alignment: Alignment?,
  val state: PlacedTowardsState? = null,
) : ModifierNodeElement<GeographicPlacementNode>() {
  override fun create() = GeographicPlacementNode(this)

  override fun update(node: GeographicPlacementNode) {
    if (node.placement.state !== state) node.placement.state?.isPlaced = false
    node.placement = this
  }

  override fun InspectorInfo.inspectableProperties() {
    name = if (alignment == null) "placedTowards" else "placedAt"
    properties["position"] = position
  }
}

internal class GeographicPlacementNode(var placement: GeographicPlacement) :
  Modifier.Node(), LayoutModifierNode {
  override fun onDetach() {
    placement.state?.isPlaced = false
  }

  override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints) =
    with(placement) {
      require(constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
        "Geographic placement needs bounded width and height"
      }
      val width = constraints.maxWidth
      val height = constraints.maxHeight
      val child = measurable.measure(Constraints())
      layout(width, height) {
        fun place(): Boolean {
          val map = mapCoordinates.value ?: return false
          val local = coordinates ?: return false
          if (!map.isAttached || mapState.viewport == null || width == 0 || height == 0)
            return false
          val screen = mapState.screenLocationFromPosition(position) ?: return false
          // localPositionOf observes its source coordinates. Read ours too so moving an
          // ancestor re-runs this placement even when the child needs no remeasurement.
          local.localToRoot(Offset.Zero)
          val target = local.localPositionOf(map, Offset(screen.x.toPx(), screen.y.toPx()))
          val topLeft =
            if (alignment == null) {
              val intersection =
                findEllipseIntersection(Rect(0f, 0f, width.toFloat(), height.toFloat()), target)
                  ?: return false
              state?.angleDegrees = (intersection.angleRadians * 180 / PI).toFloat()
              placedTowardsTopLeft(intersection, child.width, child.height).let {
                Offset(it.x.toFloat(), it.y.toFloat())
              }
            } else {
              val offset =
                alignment.align(IntSize(child.width, child.height), IntSize.Zero, layoutDirection)
              target + Offset(offset.x.toFloat(), offset.y.toFloat())
            }
          val x = topLeft.x.roundToInt()
          val y = topLeft.y.roundToInt()
          if (x + child.width < 0 || y + child.height < 0 || x > width || y > height) return false
          child.place(x, y)
          return true
        }
        val isPlaced = place()
        state?.isPlaced = isPlaced
      }
    }
}
