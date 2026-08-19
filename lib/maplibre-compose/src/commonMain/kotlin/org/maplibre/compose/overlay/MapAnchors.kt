package org.maplibre.compose.overlay

import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.Position

/**
 * Receiver for Compose UI pinned to geographic positions on a
 * [MaplibreMap][org.maplibre.compose.map.MaplibreMap].
 *
 * [Modifier.anchor] places a child so that [alignment] on that child sits on [position], the same
 * way [Modifier.align][androidx.compose.foundation.layout.BoxScope.align] places a child in a box.
 * The parent reads [CameraState.projection] during layout, so a camera move or a viewport resize
 * relocates every child without recomposing them.
 */
@LayoutScopeMarker
@Stable
public interface MapAnchorScope {
  /** The camera state of the map that these anchors belong to. */
  public val cameraState: CameraState

  /**
   * Places this child on [position]. [alignment] is the point of the child that sits on that
   * position: [Alignment.BottomCenter] puts the bottom edge on the point.
   */
  public fun Modifier.anchor(
    position: Position,
    alignment: Alignment = Alignment.Center,
  ): Modifier
}

/**
 * Lays out Compose UI on geographic positions of [cameraState].
 *
 * Size this to the map it belongs to. The default [modifier] fills the parent, which is the right
 * size when this is a sibling of the map in the same box. The projection is relative to the map
 * composable, and [MapOverlay]'s box is inset from it.
 *
 * Children that sit fully outside this layout, or that omit [Modifier.anchor], are measured and not
 * placed.
 *
 * @param cameraState The camera whose projection converts [Modifier.anchor] positions to the
 *   screen.
 * @param modifier Applied to the layout. Defaults to filling the parent.
 */
@Composable
public fun MapAnchors(
  cameraState: CameraState,
  modifier: Modifier = Modifier.fillMaxSize(),
  content: @Composable @UiComposable MapAnchorScope.() -> Unit,
) {
  val scope = remember(cameraState) { MapAnchorScopeImpl(cameraState) }
  Layout(modifier = modifier, content = { scope.content() }) { measurables, constraints ->
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
    val childConstraints = Constraints()
    val placeables = measurables.map { it.measure(childConstraints) }
    layout(width, height) {
      val projection = cameraState.projection
      if (projection == null || width == 0 || height == 0) return@layout
      measurables.forEachIndexed { index, measurable ->
        val anchor = measurable.parentData as? MapAnchorParentData ?: return@forEachIndexed
        val placeable = placeables[index]
        val screen = projection.screenLocationFromPosition(anchor.position)
        val aligned =
          anchor.alignment.align(
            size = IntSize(placeable.width, placeable.height),
            space = IntSize.Zero,
            layoutDirection = layoutDirection,
          )
        val x = screen.x.roundToPx() + aligned.x
        val y = screen.y.roundToPx() + aligned.y
        if (x + placeable.width < 0 || y + placeable.height < 0 || x > width || y > height) {
          return@forEachIndexed
        }
        // Geographic x is not mirrored in RTL; Alignment.align already resolved the child's edge.
        placeable.place(x, y)
      }
    }
  }
}

@Immutable private data class MapAnchorParentData(val position: Position, val alignment: Alignment)

private class MapAnchorScopeImpl(override val cameraState: CameraState) : MapAnchorScope {
  override fun Modifier.anchor(position: Position, alignment: Alignment): Modifier =
    this.then(MapAnchorElement(position, alignment))
}

private data class MapAnchorElement(val position: Position, val alignment: Alignment) :
  ParentDataModifier {
  override fun Density.modifyParentData(parentData: Any?): Any =
    MapAnchorParentData(position, alignment)
}
