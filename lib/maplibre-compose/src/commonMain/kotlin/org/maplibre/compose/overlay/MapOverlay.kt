package org.maplibre.compose.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.UiComposable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MapStyleState
import org.maplibre.spatialk.geojson.Position

/**
 * Receiver for the controls drawn on top of a [MaplibreMap][org.maplibre.compose.map.MaplibreMap].
 *
 * [Modifier.align] positions a child against an edge of the unobstructed region of the map.
 * [Modifier.placedAt] positions a child on a geographic position of the full map.
 * [Modifier.placedTowards] positions a child on the edge of the unobstructed region, pointing
 * towards an off-screen position. The map state that controls read is available here, which is why
 * the default controls take no arguments.
 */
@LayoutScopeMarker
@Stable
public interface MapOverlayScope {
  /** The logical map that this overlay belongs to. */
  public val mapState: MapState

  /** The style state of the map that this overlay belongs to. */
  public val style: MapStyleState
    get() = mapState.style

  /**
   * The obstructed region of the map, as passed to
   * [MaplibreMap][org.maplibre.compose.map.MaplibreMap]. [Modifier.align] already applies it;
   * [Modifier.placedAt] uses the full map.
   */
  public val contentWindowInsets: WindowInsets

  /**
   * Places this child against an edge of the unobstructed map region, the same way
   * [Modifier.align][androidx.compose.foundation.layout.BoxScope.align] places a child in a box.
   */
  public fun Modifier.align(alignment: Alignment): Modifier

  /**
   * Places this child on [position]. [alignment] is the point of the child that sits on that
   * position: [Alignment.BottomCenter] puts the bottom edge on the point.
   */
  public fun Modifier.placedAt(
    position: Position,
    alignment: Alignment = Alignment.Center,
  ): Modifier

  /**
   * Places this child on the edge of an ellipse inscribed in the unobstructed map region, on the
   * line from the ellipse center towards [position]. The anchor is the point of an ellipse
   * inscribed in the child that faces [position], so a child that draws up to its own inscribed
   * ellipse touches the placement ellipse exactly.
   *
   * The child is placed only while [position] projects outside the placement ellipse. The ellipse
   * is a stricter bound than the map region itself: a position near a corner of the region can be
   * visible on the map and still lie outside the ellipse.
   *
   * Pass a [state] to read the direction that the child points, e.g. to rotate an arrow towards the
   * target.
   */
  public fun Modifier.placedTowards(
    position: Position,
    state: PlacedTowardsState? = null,
  ): Modifier
}

/**
 * The placement that [MapOverlayScope.placedTowards] last computed for one child. Create one with
 * [rememberPlacedTowardsState] and pass it to the modifier.
 */
@Stable
public class PlacedTowardsState {
  /**
   * The direction that the child points, in degrees clockwise from screen-up. Zero until the child
   * is first placed.
   */
  public var angleDegrees: Float by mutableStateOf(0f)
    internal set

  /** Whether the child is currently placed, i.e. its target lies outside the ellipse. */
  public var isPlaced: Boolean by mutableStateOf(false)
    internal set
}

/** Remembers a [PlacedTowardsState] to pass to [MapOverlayScope.placedTowards]. */
@Composable
public fun rememberPlacedTowardsState(): PlacedTowardsState = remember { PlacedTowardsState() }

/**
 * Draws [overlay] into this overlay. Use this to keep [MapOverlay.Default] and add children of your
 * own.
 */
@Composable
@UiComposable
public fun MapOverlayScope.include(overlay: MapOverlay) {
  overlay.content(this)
}

/**
 * Reusable controls that a [MaplibreMap][org.maplibre.compose.map.MaplibreMap] draws over itself.
 */
@Immutable
public class MapOverlay(
  internal val content: @Composable @UiComposable MapOverlayScope.() -> Unit
) {
  public companion object {
    /** Gap between aligned overlay controls and the edge of the unobstructed map region. */
    public val Spacing: Dp = 8.dp

    /** No controls. Use this when the app shows the attribution somewhere else. */
    public val None: MapOverlay = MapOverlay {}

    /**
     * The MapLibre logo and an attribution button along the bottom edge.
     *
     * Most maps serve tiles under a license that requires attribution, so a map keeps these unless
     * the app shows the attribution somewhere else.
     */
    public val AttributionOnly: MapOverlay = MapOverlay {
      val overlayScope = this
      Row(
        Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        MaplibreLogo()
        overlayScope.ExpandingAttributionButton()
      }
    }

    /**
     * A scale bar and a compass along the top edge, and the controls from [AttributionOnly] along
     * the bottom edge. The scale bar and the compass appear only while they are relevant.
     *
     * A [MaplibreMap][org.maplibre.compose.map.MaplibreMap] draws these unless the caller replaces
     * them.
     */
    public val Default: MapOverlay = MapOverlay {
      DisappearingScaleBar(
        metersPerDp = mapState.viewport?.metersPerDpAtTarget ?: 0.0,
        zoom = mapState.cameraPosition.zoom,
        modifier = Modifier.align(Alignment.TopStart),
      )

      DisappearingCompassButton(modifier = Modifier.align(Alignment.TopEnd))

      include(AttributionOnly)
    }

    /**
     * The controls from [Default], plus zoom buttons above the attribution button.
     *
     * The zoom buttons complement the zoom gestures; they serve pointer devices and accessibility.
     */
    public val Full: MapOverlay = MapOverlay {
      DisappearingScaleBar(
        metersPerDp = mapState.viewport?.metersPerDpAtTarget ?: 0.0,
        zoom = mapState.cameraPosition.zoom,
        modifier = Modifier.align(Alignment.TopStart),
      )

      DisappearingCompassButton(modifier = Modifier.align(Alignment.TopEnd))

      MaplibreLogo(Modifier.align(Alignment.BottomStart))

      val overlayScope = this
      Column(
        Modifier.align(Alignment.BottomEnd),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing),
      ) {
        overlayScope.ZoomButtons()
        overlayScope.ExpandingAttributionButton()
      }
    }
  }
}

@Composable
internal fun MapOverlayHost(
  overlay: @Composable @UiComposable MapOverlayScope.() -> Unit,
  mapState: MapState,
  contentWindowInsets: WindowInsets,
  modifier: Modifier = Modifier,
) {
  val scope =
    remember(mapState, contentWindowInsets) {
      MapOverlayScopeImpl(mapState, contentWindowInsets)
    }
  Layout(
    modifier = modifier,
    content = { overlay(scope) },
  ) { measurables, constraints ->
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
    val spacing = MapOverlay.Spacing.roundToPx()
    val innerLeft = contentWindowInsets.getLeft(this, layoutDirection) + spacing
    val innerTop = contentWindowInsets.getTop(this) + spacing
    val innerRight = contentWindowInsets.getRight(this, layoutDirection) + spacing
    val innerBottom = contentWindowInsets.getBottom(this) + spacing
    val innerWidth = (width - innerLeft - innerRight).coerceAtLeast(0)
    val innerHeight = (height - innerTop - innerBottom).coerceAtLeast(0)
    val alignedConstraints = Constraints(maxWidth = innerWidth, maxHeight = innerHeight)
    val placeables = measurables.map { measurable ->
      val child = measurable.parentData as? OverlayChildData
      val childConstraints =
        when (child) {
          is OverlayChildData.PlacedAt,
          is OverlayChildData.PlacedTowards -> Constraints()
          else -> alignedConstraints
        }
      measurable.measure(childConstraints)
    }
    layout(width, height) {
      val hasPlacedAt = measurables.any {
        it.parentData is OverlayChildData.PlacedAt ||
          it.parentData is OverlayChildData.PlacedTowards
      }
      // Aligned children stay put when the camera moves. Reading the viewport here would
      // invalidate this layout on every frame of a camera ease, so it is read only when a child
      // needs placing; the read is also what re-runs this layout when the transform changes.
      val viewport = if (hasPlacedAt) mapState.viewport else null
      measurables.forEachIndexed { index, measurable ->
        val placeable = placeables[index]
        when (val child = measurable.parentData as? OverlayChildData) {
          is OverlayChildData.PlacedAt -> {
            if (viewport == null || width == 0 || height == 0) return@forEachIndexed
            val screen =
              mapState.screenLocationFromPosition(child.position) ?: return@forEachIndexed
            val aligned =
              child.alignment.align(
                size = IntSize(placeable.width, placeable.height),
                space = IntSize.Zero,
                layoutDirection = layoutDirection,
              )
            val x = screen.x.roundToPx() + aligned.x
            val y = screen.y.roundToPx() + aligned.y
            if (x + placeable.width < 0 || y + placeable.height < 0 || x > width || y > height) {
              return@forEachIndexed
            }
            // Geographic x is not mirrored in RTL; Alignment.align already resolved the child's
            // edge.
            placeable.place(x, y)
          }
          is OverlayChildData.PlacedTowards -> {
            val intersection =
              if (viewport == null || innerWidth == 0 || innerHeight == 0) null
              else {
                mapState.screenLocationFromPosition(child.position)?.let { screen ->
                  findEllipseIntersection(
                    area =
                      Rect(
                        left = innerLeft.toFloat(),
                        top = innerTop.toFloat(),
                        right = (innerLeft + innerWidth).toFloat(),
                        bottom = (innerTop + innerHeight).toFloat(),
                      ),
                    target = Offset(screen.x.toPx(), screen.y.toPx()),
                  )
                }
              }
            if (intersection == null) {
              child.state?.isPlaced = false
              return@forEachIndexed
            }
            val topLeft = placedTowardsTopLeft(intersection, placeable.width, placeable.height)
            // Geographic direction is not mirrored in RTL.
            placeable.place(topLeft.x, topLeft.y)
            child.state?.let { state ->
              state.angleDegrees = (intersection.angleRadians * 180 / PI).toFloat()
              state.isPlaced = true
            }
          }
          else -> {
            val alignment = (child as? OverlayChildData.Aligned)?.alignment ?: Alignment.TopStart
            val offset =
              alignment.align(
                size = IntSize(placeable.width, placeable.height),
                space = IntSize(innerWidth, innerHeight),
                layoutDirection = layoutDirection,
              )
            placeable.place(innerLeft + offset.x, innerTop + offset.y)
          }
        }
      }
    }
  }
}

@Stable
internal class MapOverlayScopeImpl(
  override val mapState: MapState,
  override val contentWindowInsets: WindowInsets,
) : MapOverlayScope {
  override fun Modifier.align(alignment: Alignment): Modifier = this.then(AlignElement(alignment))

  override fun Modifier.placedAt(position: Position, alignment: Alignment): Modifier =
    this.then(PlacedAtElement(position, alignment))

  override fun Modifier.placedTowards(position: Position, state: PlacedTowardsState?): Modifier =
    this.then(PlacedTowardsElement(position, state))
}

@Immutable
private sealed class OverlayChildData {
  class Aligned(val alignment: Alignment) : OverlayChildData()

  class PlacedAt(val position: Position, val alignment: Alignment) : OverlayChildData()

  class PlacedTowards(val position: Position, val state: PlacedTowardsState?) : OverlayChildData()
}

private data class AlignElement(val alignment: Alignment) : ParentDataModifier {
  override fun Density.modifyParentData(parentData: Any?): Any =
    when (parentData) {
      is OverlayChildData.PlacedAt,
      is OverlayChildData.PlacedTowards -> parentData
      else -> OverlayChildData.Aligned(alignment)
    }
}

private data class PlacedAtElement(val position: Position, val alignment: Alignment) :
  ParentDataModifier {
  override fun Density.modifyParentData(parentData: Any?): Any =
    OverlayChildData.PlacedAt(position, alignment)
}

// A node element rather than a plain ParentDataModifier: onDetach resets the state when the
// child leaves the composition, so a hoisted state never reports a placement that no longer
// exists.
private data class PlacedTowardsElement(val position: Position, val state: PlacedTowardsState?) :
  ModifierNodeElement<PlacedTowardsNode>() {
  override fun create(): PlacedTowardsNode = PlacedTowardsNode(position, state)

  override fun update(node: PlacedTowardsNode) {
    if (node.state != state) node.state?.isPlaced = false
    node.position = position
    node.state = state
  }
}

private class PlacedTowardsNode(var position: Position, var state: PlacedTowardsState?) :
  Modifier.Node(), ParentDataModifierNode {
  override fun Density.modifyParentData(parentData: Any?): Any =
    OverlayChildData.PlacedTowards(position, state)

  override fun onDetach() {
    state?.isPlaced = false
  }
}
