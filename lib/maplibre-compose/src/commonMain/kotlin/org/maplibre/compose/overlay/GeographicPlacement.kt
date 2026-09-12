package org.maplibre.compose.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.roundToInt
import org.maplibre.spatialk.geojson.Position

/**
 * Places [content] at a geographic [position]. [alignment] selects the point of the content that
 * sits on that position. For example, [Alignment.BottomCenter] places a label above the position.
 *
 * This layout fills the available space and measures its content without size constraints. It can
 * be nested in padded or offset layouts; geographic positions always refer to the original map.
 * Content is not placed until the map has a viewport, or when it is entirely outside this layout.
 * Padding on [modifier] changes this layout's bounds, not the geographic position.
 */
@Composable
public fun MapOverlayScope.AtPosition(
  position: Position,
  modifier: Modifier = Modifier,
  alignment: Alignment = Alignment.Center,
  content: @Composable () -> Unit,
) {
  GeographicPlacement(position, modifier, alignment, towards = false, state = null, content)
}

/**
 * Places [content] on an ellipse inscribed in this layout, pointing towards [position]. Only places
 * the content while the position projects outside that ellipse. The point on the content's own
 * inscribed ellipse that faces the target touches the placement ellipse.
 *
 * This layout fills the available space. Use ordinary padding and sizing on [modifier] or a parent
 * layout to choose the placement region. Nested layouts convert from map coordinates automatically.
 * Pass [state] to read the direction, for example to rotate an indicator.
 */
@Composable
public fun MapOverlayScope.TowardsPosition(
  position: Position,
  modifier: Modifier = Modifier,
  state: PlacedTowardsState? = null,
  content: @Composable () -> Unit,
) {
  DisposableEffect(state) { onDispose { state?.isPlaced = false } }
  GeographicPlacement(position, modifier, Alignment.Center, towards = true, state, content)
}

/** Placement last computed by [MapOverlayScope.TowardsPosition]. */
@Stable
public class PlacedTowardsState {
  /** Direction in degrees clockwise from screen-up. Zero until the first placement. */
  public var angleDegrees: Float by mutableFloatStateOf(0f)
    internal set

  /** Whether the content is currently placed. */
  public var isPlaced: Boolean by mutableStateOf(false)
    internal set
}

/** Remembers a [PlacedTowardsState] for [MapOverlayScope.TowardsPosition]. */
@Composable
public fun rememberPlacedTowardsState(): PlacedTowardsState = remember { PlacedTowardsState() }

@Composable
private fun MapOverlayScope.GeographicPlacement(
  position: Position,
  modifier: Modifier,
  alignment: Alignment,
  towards: Boolean,
  state: PlacedTowardsState?,
  content: @Composable () -> Unit,
) {
  val mapCoordinates = (this as MapOverlayScopeImpl).coordinates
  Layout(content = { Box { content() } }, modifier = modifier) { measurables, constraints ->
    require(constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
      "Geographic placement needs bounded width and height"
    }
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val child = measurables.single().measure(Constraints())
    layout(width, height) {
      state?.isPlaced = false
      val map = mapCoordinates.value ?: return@layout
      val local = coordinates ?: return@layout
      if (!map.isAttached || mapState.viewport == null || width == 0 || height == 0) return@layout
      val screen = mapState.screenLocationFromPosition(position) ?: return@layout
      // Reading coordinates during placement also makes Compose re-place us when a parent moves.
      val target = local.localPositionOf(map, Offset(screen.x.toPx(), screen.y.toPx()))
      val topLeft =
        if (towards) {
          val intersection =
            findEllipseIntersection(Rect(0f, 0f, width.toFloat(), height.toFloat()), target)
              ?: return@layout
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
      if (x + child.width < 0 || y + child.height < 0 || x > width || y > height) return@layout
      child.place(x, y)
      state?.isPlaced = true
    }
  }
}
