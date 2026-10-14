package org.maplibre.compose.interaction.internal

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputEventHandler
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/** Cancels the map's handler and its continuations even when the host has no pressed pointers. */
internal fun Modifier.mapPointerInput(
  vararg keys: Any?,
  handler: PointerInputEventHandler,
): Modifier = then(MapPointerInputElement(keys.toList(), handler))

private data class MapPointerInputElement(
  val keys: List<Any?>,
  val handler: PointerInputEventHandler,
) : ModifierNodeElement<MapPointerInputNode>() {
  override fun create() = MapPointerInputNode(keys, handler)

  override fun update(node: MapPointerInputNode) = node.update(keys, handler)

  override fun InspectorInfo.inspectableProperties() {
    name = "mapPointerInput"
  }
}

private class MapPointerInputNode(
  private var keys: List<Any?>,
  private var handler: PointerInputEventHandler,
) : DelegatingNode(), PointerInputModifierNode {
  private val input = delegate(SuspendingPointerInputModifierNode { with(handler) { invoke() } })

  fun update(keys: List<Any?>, handler: PointerInputEventHandler) {
    if (this.keys != keys) input.resetPointerInputHandler()
    this.keys = keys
    this.handler = handler
  }

  override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) =
    input.onPointerEvent(pointerEvent, pass, bounds)

  override fun onCancelPointerInput() {
    input.onCancelPointerInput()
    // Compose's suspending handler only synthesizes cancellation for pressed pointers. Native
    // scale events use a hovering mouse pointer; reset explicitly so the router's finally runs.
    input.resetPointerInputHandler()
  }
}
