package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/** Touch target of a corner handle; the visual dot is much smaller. */
private val HandleTouchTarget = 48.dp
private val HandleDotSize = 16.dp

/** Arm length cap for the corner brackets, so small frames keep their thirds grid readable. */
private val BracketArm = 26.dp

/** The viewfinder fills the space that the surrounding column leaves above its controls. */
@Composable
internal fun SnapshotFrame(
  aspect: SnapshotAspect,
  modifier: Modifier = Modifier,
  onPositioned: (LayoutCoordinates) -> Unit,
) {
  var preferredSize by remember { mutableStateOf<DpSize?>(null) }
  var dragging by remember { mutableStateOf(false) }
  BoxWithConstraints(modifier.fillMaxSize()) {
    val safe = DpSize(maxWidth, maxHeight)
    val target =
      fitFrameToSafeArea(preferredSize ?: DpSize(maxWidth * 0.62f, maxHeight * 0.62f), safe, aspect)
    val animated by
      animateValueAsState(
        target,
        DpSizeVectorConverter,
        animationSpec = if (dragging) snap() else MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "frame size",
      )
    val size = fitFrameToSafeArea(animated, safe, SnapshotAspect.Free)
    val frame = centeredFrame(size, safe)
    Box(Modifier.align(Alignment.Center).size(size).onGloballyPositioned(onPositioned))
    if (size.width >= HandleTouchTarget && size.height >= HandleTouchTarget) {
      FrameCorner.entries.forEach { corner ->
        FrameHandle(
          corner,
          frame,
          onDragging = { dragging = it },
          onDrag = { drag -> preferredSize = resizedFrame(size, corner, drag, safe, aspect) },
        )
      }
    }
  }
}

private val DpSizeVectorConverter =
  TwoWayConverter<DpSize, AnimationVector2D>(
    convertToVector = { AnimationVector2D(it.width.value, it.height.value) },
    convertFromVector = { DpSize(it.v1.dp, it.v2.dp) },
  )

/** Full-map drawing uses the measured frame's map-relative pixel bounds. */
@Composable
internal fun SnapshotScrim(frame: Rect?) {
  val scrimPath = remember { Path() }
  Canvas(Modifier.fillMaxSize()) {
    scrimPath.rewind()
    scrimPath.fillType = PathFillType.EvenOdd
    scrimPath.addRect(Rect(Offset.Zero, size))
    if (frame != null) scrimPath.addRoundRect(RoundRect(frame, CornerRadius(4.dp.toPx())))
    drawPath(scrimPath, Color.Black.copy(alpha = 0.38f))
    val framePx = frame ?: return@Canvas

    val gridColor = Color.White.copy(alpha = 0.3f)
    val gridStroke = 1.dp.toPx()
    for (i in 1..2) {
      val x = framePx.left + framePx.width * i / 3f
      val y = framePx.top + framePx.height * i / 3f
      drawLine(gridColor, Offset(x, framePx.top), Offset(x, framePx.bottom), gridStroke)
      drawLine(gridColor, Offset(framePx.left, y), Offset(framePx.right, y), gridStroke)
    }

    val arm = min(BracketArm.toPx(), min(framePx.width, framePx.height) / 3f)
    // A dark halo keeps the white brackets readable on bright basemaps.
    drawBrackets(framePx, arm, Color.Black.copy(alpha = 0.35f), 5.5f.dp.toPx())
    drawBrackets(framePx, arm, Color.White, 3.dp.toPx())
  }
}

private fun DrawScope.drawBrackets(frame: Rect, arm: Float, color: Color, strokeWidth: Float) {
  for (corner in FrameCorner.entries) {
    val anchor =
      Offset(
        x = if (corner.signX < 0) frame.left else frame.right,
        y = if (corner.signY < 0) frame.top else frame.bottom,
      )
    drawLine(
      color,
      start = anchor - Offset(arm * corner.signX, 0f),
      end = anchor,
      strokeWidth = strokeWidth,
      cap = StrokeCap.Round,
    )
    drawLine(
      color,
      start = anchor - Offset(0f, arm * corner.signY),
      end = anchor,
      strokeWidth = strokeWidth,
      cap = StrokeCap.Round,
    )
  }
}

@Composable
private fun FrameHandle(
  corner: FrameCorner,
  frame: Rect,
  onDragging: (Boolean) -> Unit,
  onDrag: (DpOffset) -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  var dragging by remember { mutableStateOf(false) }
  // The gesture below never restarts, so it must reach the latest onDrag (which closes over the
  // current safe area) rather than the one from first composition.
  val currentOnDrag by rememberUpdatedState(onDrag)
  val scale by
    animateFloatAsState(
      targetValue = if (dragging) 1.35f else if (hovered) 1.15f else 1f,
      animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
      label = "handle scale",
    )
  val center =
    Offset(
      x = if (corner.signX < 0) frame.left else frame.right,
      y = if (corner.signY < 0) frame.top else frame.bottom,
    )
  val description =
    when (corner) {
      FrameCorner.TopLeft -> "Resize frame from top left"
      FrameCorner.TopRight -> "Resize frame from top right"
      FrameCorner.BottomLeft -> "Resize frame from bottom left"
      FrameCorner.BottomRight -> "Resize frame from bottom right"
    }
  // Drags are pointer-only, so grow and shrink also get semantics actions assistive tech can
  // invoke. A nudge mirrors a 24 dp diagonal drag through this corner.
  val grow = DpOffset(24.dp * corner.signX, 24.dp * corner.signY)
  val shrink = DpOffset(-24.dp * corner.signX, -24.dp * corner.signY)
  Box(
    modifier =
      Modifier.offset {
          IntOffset(
            (center.x.dp.toPx() - HandleTouchTarget.toPx() / 2).roundToInt(),
            (center.y.dp.toPx() - HandleTouchTarget.toPx() / 2).roundToInt(),
          )
        }
        .size(HandleTouchTarget)
        .semantics {
          contentDescription = description
          set(
            SemanticsActions.CustomActions,
            listOf(
              CustomAccessibilityAction(
                label = "Grow frame",
                action = {
                  onDrag(grow)
                  true
                },
              ),
              CustomAccessibilityAction(
                label = "Shrink frame",
                action = {
                  onDrag(shrink)
                  true
                },
              ),
            ),
          )
        }
        .pointerHoverIcon(PointerIcon.Hand)
        .hoverable(interactionSource)
        .pointerInput(corner) {
          detectDragGestures(
            onDragStart = {
              dragging = true
              onDragging(true)
            },
            onDragEnd = {
              dragging = false
              onDragging(false)
            },
            onDragCancel = {
              dragging = false
              onDragging(false)
            },
          ) { change, dragAmount ->
            change.consume()
            currentOnDrag(DpOffset(dragAmount.x.toDp(), dragAmount.y.toDp()))
          }
        },
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier.size(HandleDotSize)
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        }
        .shadow(3.dp, CircleShape)
        .background(MaterialTheme.colorScheme.primary, CircleShape)
        .border(2.dp, Color.White, CircleShape)
    )
  }
}
