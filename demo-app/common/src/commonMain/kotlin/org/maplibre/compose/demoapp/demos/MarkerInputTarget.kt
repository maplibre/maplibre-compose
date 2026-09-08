package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@Composable
internal fun MarkerInputTarget(
  modifier: Modifier,
  enabled: Boolean,
  onHover: (Boolean) -> Unit,
  onPress: (Offset) -> Unit,
  onDragStart: () -> Unit,
  onDrag: (position: Offset, distance: DpOffset, delta: Offset) -> Unit,
  onTap: () -> Unit,
  onDragEnd: () -> Unit,
  onFinish: () -> Unit,
) {
  if (!enabled) return

  val hover by rememberUpdatedState(onHover)
  val press by rememberUpdatedState(onPress)
  val dragStart by rememberUpdatedState(onDragStart)
  val drag by rememberUpdatedState(onDrag)
  val tap by rememberUpdatedState(onTap)
  val dragEnd by rememberUpdatedState(onDragEnd)
  val finish by rememberUpdatedState(onFinish)
  var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
  var hovered by remember { mutableStateOf(false) }
  Box(
    // The pin tip is the geographic anchor; the padded body extends 58 dp above it.
    modifier
      .offset(y = 10.dp)
      .size(60.dp, 68.dp)
      .onGloballyPositioned { coordinates = it }
      .clip(MarkerHitShape)
      .pointerHoverIcon(if (hovered) PointerIcon.Hand else PointerIcon.Default)
      .pointerInput(Unit) {
        var pointer: PointerId? = null
        var dragging = false
        var down = Offset.Zero
        var previous = Offset.Zero
        var swallowing = false
        try {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent(PointerEventPass.Initial)
              val change = event.changes.firstOrNull() ?: continue
              val x = (change.position.x - size.width / 2f) / (size.width / 2f)
              val y = (change.position.y - size.height / 2f) / (size.height / 2f)
              val hit = x * x + y * y <= 1f
              if (event.type == PointerEventType.Exit) {
                hovered = false
                hover(false)
              } else if (change.type == PointerType.Mouse && !change.pressed) {
                hovered = hit && !change.isConsumed
                hover(hovered)
              }
              if (swallowing) {
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) swallowing = false
                continue
              }
              val layout = coordinates?.takeIf { it.isAttached } ?: continue
              if (
                pointer == null &&
                  hit &&
                  event.type == PointerEventType.Press &&
                  event.changes.count { it.pressed } == 1 &&
                  !change.isConsumed &&
                  (change.type != PointerType.Mouse || event.buttons.isPrimaryPressed)
              ) {
                pointer = change.id
                down = layout.localToRoot(change.position)
                previous = down
                press(down)
              }
              val captured = pointer ?: continue
              val tracked = event.changes.firstOrNull { it.id == captured }
              if (tracked == null || event.changes.count { it.pressed } > 1) {
                pointer = null
                dragging = false
                finish()
                swallowing = event.changes.any { it.pressed }
                event.changes.forEach { it.consume() }
                continue
              }
              tracked.consume()
              // The target follows the marker during a drag. Local deltas would feed that
              // movement back into the gesture, so retain the original press in root space.
              val position = layout.localToRoot(tracked.position)
              val distance = position - down
              if (
                tracked.pressed && !dragging && distance.getDistance() > viewConfiguration.touchSlop
              ) {
                dragging = true
                dragStart()
              }
              if (dragging) {
                drag(position, DpOffset(distance.x.toDp(), distance.y.toDp()), position - previous)
              }
              previous = position
              if (!tracked.pressed) {
                if (dragging) dragEnd() else tap()
                pointer = null
                dragging = false
                finish()
              }
            }
          }
        } finally {
          hovered = false
          hover(false)
          if (pointer != null) finish()
        }
      }
  )
}

private val MarkerHitShape = GenericShape { size, _ -> addOval(Rect(Offset.Zero, size)) }
