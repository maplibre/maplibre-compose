package org.maplibre.compose.demoapp.demos.snapshotter

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Touch target of a corner handle; the visual dot is much smaller. */
private val HandleTouchTarget = 48.dp
private val HandleDotSize = 16.dp

/** Arm length cap for the corner brackets, so small frames keep their thirds grid readable. */
private val BracketArm = 26.dp

/**
 * The capture viewfinder: a scrim dimming the whole map with a hole for the frame, a thirds grid,
 * corner brackets, and one drag handle per corner. It draws over the map without eating its
 * gestures; only the handles take pointer input.
 *
 * The frame itself centers on the safe area; [fullMap] is the whole map's size, which lets the
 * scrim reach past the safe area to the map's edges. The scrim only draws, so extending past this
 * child's layout bounds is safe — nothing clips on the way to the map.
 *
 * [bottomClearance] reserves space for the bottom capture controls, so the frame's handles never
 * reach under them on narrow hosts.
 */
@Composable
internal fun SnapshotFrame(
  state: SnapshotterDemoState,
  safe: DpSize,
  originDp: DpOffset,
  fullMap: DpSize?,
  bottomClearance: Dp = 0.dp,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val frameSize = remember { Animatable(DpSize.Zero, DpSizeVectorConverter) }
  val motion = MaterialTheme.motionScheme
  val frameArea =
    DpSize(
      safe.width,
      (safe.height - bottomClearance).coerceAtLeast(FrameMinSize + FrameMargin * 2),
    )

  // First layout picks a default; later safe-area changes only clamp the frame back inside.
  LaunchedEffect(frameArea) {
    if (frameArea.width <= FrameMargin * 2 || frameArea.height <= FrameMargin * 2) {
      return@LaunchedEffect
    }
    if (state.frameSize == DpSize.Zero) {
      val initial = defaultFrameSize(frameArea, state.aspect)
      state.frameSize = initial
      frameSize.snapTo(initial)
    } else {
      state.frameSize = fitFrameToSafeArea(state.frameSize, frameArea, state.aspect)
    }
  }
  LaunchedEffect(state.aspect) {
    if (state.frameSize != DpSize.Zero) {
      state.frameSize = refitFrame(state.frameSize, frameArea, state.aspect)
    }
  }
  LaunchedEffect(state.frameSize) {
    if (state.frameSize != DpSize.Zero && frameSize.value != state.frameSize) {
      // A returning composition starts from zero; snapping there avoids a grow-from-center
      // animation on every visit. Live edits animate.
      if (frameSize.value == DpSize.Zero) frameSize.snapTo(state.frameSize)
      else frameSize.animateTo(state.frameSize, motion.defaultSpatialSpec())
    }
  }

  // The capture request and the reveal flight read the frame from map coordinates. The flow must
  // read the current frame area and origin, not the values captured at its first composition.
  val currentArea by rememberUpdatedState(frameArea)
  val currentOrigin by rememberUpdatedState(originDp)
  LaunchedEffect(Unit) {
    snapshotFlow {
      if (frameSize.value == DpSize.Zero) null
      else
        centeredFrame(frameSize.value, currentArea)
          .translate(Offset(currentOrigin.x.value, currentOrigin.y.value))
    }
      .collect { state.frameBounds = it }
  }

  if (frameSize.value == DpSize.Zero) return

  val frame = centeredFrame(frameSize.value, frameArea)

  ScrimAndBrackets(frame, fullMap, originDp, modifier)

  FrameCorner.entries.forEach { corner ->
    key(corner) {
      FrameHandle(
        corner = corner,
        frame = frame,
        onDrag = { drag ->
          val resized = resizedFrame(state.frameSize, corner, drag, frameArea, state.aspect)
          state.frameSize = resized
          scope.launch { frameSize.snapTo(resized) }
        },
      )
    }
  }
}

private val DpSizeVectorConverter =
  TwoWayConverter<DpSize, AnimationVector2D>(
    convertToVector = { AnimationVector2D(it.width.value, it.height.value) },
    convertFromVector = { DpSize(it.v1.dp, it.v2.dp) },
  )

@Composable
private fun ScrimAndBrackets(
  frame: Rect,
  fullMap: DpSize?,
  originDp: DpOffset,
  modifier: Modifier = Modifier,
) {
  val scrimColor = Color.Black.copy(alpha = 0.38f)
  val scrimPath = remember { Path() }
  Canvas(modifier.fillMaxSize()) {
    val left = frame.left.dp.toPx()
    val top = frame.top.dp.toPx()
    val width = frame.width.dp.toPx()
    val height = frame.height.dp.toPx()
    val framePx = Rect(left, top, left + width, top + height)

    // The outer rect reaches from this child's top left to the map's far edges, so the dimming
    // covers the full map rather than stopping at the safe area.
    val outer =
      if (fullMap == null) Rect(Offset.Zero, size)
      else
        Rect(
          left = -originDp.x.toPx(),
          top = -originDp.y.toPx(),
          right = fullMap.width.toPx() - originDp.x.toPx(),
          bottom = fullMap.height.toPx() - originDp.y.toPx(),
        )

    scrimPath.rewind()
    scrimPath.fillType = PathFillType.EvenOdd
    scrimPath.addRect(outer)
    scrimPath.addRoundRect(RoundRect(framePx, CornerRadius(4.dp.toPx())))
    drawPath(scrimPath, scrimColor)

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
private fun FrameHandle(corner: FrameCorner, frame: Rect, onDrag: (DpOffset) -> Unit) {
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
            onDragStart = { dragging = true },
            onDragEnd = { dragging = false },
            onDragCancel = { dragging = false },
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
