package org.maplibre.compose.overlay

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.roundToInt
import org.maplibre.compose.map.LocalMapState
import org.maplibre.spatialk.geojson.Position

/** Layout operations for direct children of a map overlay or [GeographicLayout]. */
@Stable
public interface MapOverlayScope : BoxScope {
  /**
   * Places this child at [position]. [alignment] selects the child's anchor point. The child is
   * hidden before a viewport exists or when entirely outside the layout. Apply this modifier to a
   * direct child; sizing and padding describe the child itself.
   */
  public fun Modifier.placedAt(
    position: Position,
    alignment: Alignment = Alignment.Center,
  ): Modifier

  /**
   * Places this child at the edge of the layout's inscribed ellipse towards [position]. The child
   * is hidden while the position is inside the ellipse or no viewport exists. The point on the
   * child's inscribed ellipse facing the target touches the layout's ellipse. Apply this modifier
   * to a direct child. Size or pad [GeographicLayout] to change the region.
   */
  public fun Modifier.placedTowards(position: Position, state: PlacedTowardsState? = null): Modifier
}

internal object MapOverlayScopeInstance : MapOverlayScope {
  override fun Modifier.placedAt(position: Position, alignment: Alignment): Modifier =
    then(OverlayChildElement(position = position, alignment = alignment))

  override fun Modifier.placedTowards(position: Position, state: PlacedTowardsState?): Modifier =
    then(OverlayChildElement(position = position, state = state))

  override fun Modifier.align(alignment: Alignment): Modifier =
    then(OverlayChildElement(alignment = alignment))

  override fun Modifier.matchParentSize(): Modifier =
    then(OverlayChildElement(matchParentSize = true))
}

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

internal data class OverlayChildData(
  val position: Position? = null,
  val alignment: Alignment? = null,
  val state: PlacedTowardsState? = null,
  val matchParentSize: Boolean = false,
)

private data class OverlayChildElement(
  val position: Position? = null,
  val alignment: Alignment? = null,
  val state: PlacedTowardsState? = null,
  val matchParentSize: Boolean = false,
) : ModifierNodeElement<OverlayChildNode>() {
  override fun create() = OverlayChildNode(this)

  override fun update(node: OverlayChildNode) {
    if (node.element.state !== state) node.element.state?.isPlaced = false
    node.element = this
  }

  override fun InspectorInfo.inspectableProperties() {
    name =
      when {
        matchParentSize -> "matchParentSize"
        position == null -> "align"
        alignment == null -> "placedTowards"
        else -> "placedAt"
      }
    properties["position"] = position
    properties["alignment"] = alignment
  }
}

private class OverlayChildNode(var element: OverlayChildElement) :
  Modifier.Node(), ParentDataModifierNode {
  override fun onDetach() {
    element.state?.isPlaced = false
  }

  override fun Density.modifyParentData(parentData: Any?): Any {
    val data = parentData as? OverlayChildData ?: OverlayChildData()
    return if (element.matchParentSize) data.copy(matchParentSize = true)
    else
      data.copy(position = element.position, alignment = element.alignment, state = element.state)
  }
}

/**
 * A geographic layout inside an enclosing map overlay. Fills the available bounded space and
 * positions direct children with [MapOverlayScope.placedAt], [MapOverlayScope.placedTowards], or
 * [BoxScope.align]. Unpositioned children sit at the top-start. Geographic children are measured
 * without constraints; ordinary children are constrained to the layout's size.
 *
 * Size and pad this layout to choose a placement region. Positions still refer to the enclosing
 * map, including when this layout moves. This layout supplies no map state of its own.
 */
@Composable
public fun GeographicLayout(
  modifier: Modifier = Modifier,
  content: @Composable MapOverlayScope.() -> Unit,
) {
  val mapState = checkNotNull(LocalMapState.current) { "GeographicLayout requires a map overlay" }
  val mapCoordinates = LocalMapCoordinates.current
  Layout(modifier = modifier, content = { MapOverlayScopeInstance.content() }) {
    measurables,
    constraints ->
    require(constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
      "GeographicLayout needs bounded width and height"
    }
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val children = measurables.map { measurable ->
      val data = measurable.parentData as? OverlayChildData ?: OverlayChildData()
      val childConstraints =
        when {
          data.matchParentSize -> Constraints.fixed(width, height)
          data.position != null -> Constraints()
          else -> constraints.copy(minWidth = 0, minHeight = 0)
        }
      measurable.measure(childConstraints) to data
    }
    layout(width, height) {
      val local = coordinates
      val map = mapCoordinates.value
      // Observe this layout's root position as well as the source map's coordinates.
      local?.localToRoot(Offset.Zero)
      for ((child, data) in children) {
        if (data.position == null) {
          val offset =
            (data.alignment ?: Alignment.TopStart).align(
              IntSize(child.width, child.height),
              IntSize(width, height),
              layoutDirection,
            )
          child.place(offset)
          continue
        }
        fun place(): Boolean {
          if (
            local == null ||
              map == null ||
              !map.isAttached ||
              mapState.viewport == null ||
              width == 0 ||
              height == 0
          )
            return false
          val screen = mapState.screenLocationFromPosition(data.position) ?: return false
          val target = local.localPositionOf(map, Offset(screen.x.toPx(), screen.y.toPx()))
          val topLeft =
            if (data.alignment == null) {
              val intersection =
                findEllipseIntersection(Rect(0f, 0f, width.toFloat(), height.toFloat()), target)
                  ?: return false
              data.state?.angleDegrees = (intersection.angleRadians * 180 / PI).toFloat()
              placedTowardsTopLeft(intersection, child.width, child.height).let {
                Offset(it.x.toFloat(), it.y.toFloat())
              }
            } else {
              val offset =
                data.alignment.align(
                  IntSize(child.width, child.height),
                  IntSize.Zero,
                  layoutDirection,
                )
              target + Offset(offset.x.toFloat(), offset.y.toFloat())
            }
          val x = topLeft.x.roundToInt()
          val y = topLeft.y.roundToInt()
          if (x + child.width < 0 || y + child.height < 0 || x > width || y > height) return false
          child.place(x, y)
          return true
        }
        val isPlaced = place()
        data.state?.isPlaced = isPlaced
      }
    }
  }
}
