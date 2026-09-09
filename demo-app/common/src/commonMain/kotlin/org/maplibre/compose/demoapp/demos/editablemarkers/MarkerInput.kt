package org.maplibre.compose.demoapp.demos.editablemarkers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.maplibre.compose.overlay.MapOverlayScope

@Composable
internal fun MapOverlayScope.MarkerTargets(state: EditableMarkersState) =
  with(state) {
    val activeId = pressedId ?: draggingId
    for (marker in markers) key(marker.id) {
      var anchor by remember { mutableStateOf<DpOffset?>(null) }
      var trashTarget by remember { mutableStateOf<MarkerTrashTarget?>(null) }
      MarkerInputTarget(
        modifier = Modifier.placedAt(marker.position, Alignment.BottomCenter),
        enabled = !marker.removing && (activeId == null || activeId == marker.id),
        onHover = { hovered ->
          if (hovered) hoveredId = marker.id else if (hoveredId == marker.id) hoveredId = null
        },
        onPress = { rootPosition ->
          anchor = mapState.screenLocationFromPosition(marker.position)
          trashTarget = MarkerTrashTarget(rootPosition)
          pressedId = marker.id
        },
        onDragStart = {
          editingId = null
          draggingId = marker.id
          pressedId = null
        },
        onDrag = { rootPosition, distance, delta ->
          overTrash = trashTarget?.update(rootPosition, trashBounds) == true
          trashNeedsExit = trashTarget?.needsExit == true
          anchor?.let { start ->
            mapState.positionFromScreenLocation(start + distance)?.let { marker.position = it }
          }
          dragTilt = (delta.x * 0.7f).coerceIn(-18f, 18f)
        },
        onTap = { select(marker) },
        onDragEnd = { if (overTrash) remove(marker) else marker.bounce++ },
        onFinish = {
          if (pressedId == marker.id || draggingId == marker.id) endGesture()
        },
      )
    }
  }

@Composable
private fun MarkerInputTarget(
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
        val gesture =
          MarkerGesture(
            touchSlop = viewConfiguration.touchSlop,
            onPress = { press(it) },
            onDragStart = { dragStart() },
            onDrag = { position, distance, delta ->
              drag(position, DpOffset(distance.x.toDp(), distance.y.toDp()), delta)
            },
            onTap = { tap() },
            onDragEnd = { dragEnd() },
            onFinish = { finish() },
          )
        try {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent(PointerEventPass.Initial)
              val change = event.changes.firstOrNull() ?: continue
              val hit = markerHit(change.position, size)
              event.markerHover(hit)?.let {
                hovered = it
                hover(it)
              }
              gesture.handle(event, hit, coordinates?.takeIf { it.isAttached })
            }
          }
        } finally {
          hovered = false
          hover(false)
          gesture.cancel()
        }
      }
  )
}

private val MarkerHitShape = GenericShape { size, _ -> addOval(Rect(Offset.Zero, size)) }

private fun markerHit(position: Offset, size: IntSize): Boolean {
  val x = (position.x - size.width / 2f) / (size.width / 2f)
  val y = (position.y - size.height / 2f) / (size.height / 2f)
  return x * x + y * y <= 1f
}

private fun PointerEvent.markerHover(hit: Boolean): Boolean? {
  val change = changes.firstOrNull() ?: return null
  return when {
    type == PointerEventType.Exit -> false
    change.type == PointerType.Mouse && !change.pressed -> hit && !change.isConsumed
    else -> null
  }
}

private class MarkerGesture(
  private val touchSlop: Float,
  private val onPress: (Offset) -> Unit,
  private val onDragStart: () -> Unit,
  private val onDrag: (position: Offset, distance: Offset, delta: Offset) -> Unit,
  private val onTap: () -> Unit,
  private val onDragEnd: () -> Unit,
  private val onFinish: () -> Unit,
) {
  private var pointer: PointerId? = null
  private var dragging = false
  private var down = Offset.Zero
  private var previous = Offset.Zero
  private var swallowing = false

  fun handle(event: PointerEvent, hit: Boolean, layout: LayoutCoordinates?) {
    if (swallowing) {
      event.changes.forEach { it.consume() }
      if (event.changes.none { it.pressed }) swallowing = false
      return
    }
    if (layout == null) return
    if (pointer == null) tryPress(event, hit, layout)
    val captured = pointer ?: return
    val tracked = event.changes.firstOrNull { it.id == captured }
    if (tracked == null || event.changes.count { it.pressed } > 1) {
      cancel()
      swallowing = event.changes.any { it.pressed }
      event.changes.forEach { it.consume() }
      return
    }
    move(tracked, layout)
    if (!tracked.pressed) {
      if (dragging) onDragEnd() else onTap()
      cancel()
    }
  }

  private fun tryPress(event: PointerEvent, hit: Boolean, layout: LayoutCoordinates) {
    val change = event.changes.firstOrNull() ?: return
    if (
      hit &&
        event.type == PointerEventType.Press &&
        event.changes.count { it.pressed } == 1 &&
        !change.isConsumed &&
        (change.type != PointerType.Mouse || event.buttons.isPrimaryPressed)
    ) {
      pointer = change.id
      down = layout.localToRoot(change.position)
      previous = down
      onPress(down)
    }
  }

  private fun move(change: PointerInputChange, layout: LayoutCoordinates) {
    change.consume()
    // The target follows the marker. Root coordinates avoid feeding that movement
    // back into the drag distance.
    val position = layout.localToRoot(change.position)
    val distance = position - down
    if (change.pressed && !dragging && distance.getDistance() > touchSlop) {
      dragging = true
      onDragStart()
    }
    if (dragging) onDrag(position, distance, position - previous)
    previous = position
  }

  fun cancel() {
    if (pointer == null) return
    pointer = null
    dragging = false
    onFinish()
  }
}
