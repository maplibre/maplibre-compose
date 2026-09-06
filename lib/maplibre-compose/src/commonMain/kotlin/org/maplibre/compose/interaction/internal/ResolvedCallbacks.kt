package org.maplibre.compose.interaction.internal

import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.DoubleTapEvent
import org.maplibre.compose.interaction.DragEvent
import org.maplibre.compose.interaction.HoverEvent
import org.maplibre.compose.interaction.LongClickEvent
import org.maplibre.compose.interaction.PinchEvent
import org.maplibre.compose.interaction.RotateEvent
import org.maplibre.compose.interaction.ScrollEvent
import org.maplibre.compose.interaction.ShoveEvent
import org.maplibre.compose.interaction.TapEvent

internal data class DragHandlers(
  val onStart: ((DragEvent.Start) -> Unit)? = null,
  val onDelta: ((DragEvent.Delta) -> Unit)? = null,
  val onEnd: ((DragEvent.End) -> Unit)? = null,
  val onCancel: ((DragEvent.Cancel) -> Unit)? = null,
)

internal data class ZoomHandlers(
  val onStart: ((PinchEvent.Start) -> Unit)? = null,
  val onDelta: ((PinchEvent.Delta) -> Unit)? = null,
  val onEnd: ((PinchEvent.End) -> Unit)? = null,
  val onCancel: ((PinchEvent.Cancel) -> Unit)? = null,
)

internal data class RotateHandlers(
  val onStart: ((RotateEvent.Start) -> Unit)? = null,
  val onDelta: ((RotateEvent.Delta) -> Unit)? = null,
  val onEnd: ((RotateEvent.End) -> Unit)? = null,
  val onCancel: ((RotateEvent.Cancel) -> Unit)? = null,
)

internal data class TiltHandlers(
  val onStart: ((ShoveEvent.Start) -> Unit)? = null,
  val onDelta: ((ShoveEvent.Delta) -> Unit)? = null,
  val onEnd: ((ShoveEvent.End) -> Unit)? = null,
  val onCancel: ((ShoveEvent.Cancel) -> Unit)? = null,
)

internal data class ScrollHandlers(
  val onStart: ((ScrollEvent.Start) -> Unit)? = null,
  val onDelta: ((ScrollEvent.Delta) -> Unit)? = null,
  val onEnd: ((ScrollEvent.End) -> Unit)? = null,
  val onCancel: ((ScrollEvent.Cancel) -> Unit)? = null,
)

internal data class InteractionCallbacks(
  val click: ((TapEvent) -> ClickResult)? = null,
  val unhandledClick: ((TapEvent) -> ClickResult)? = null,
  val doubleClick: ((DoubleTapEvent) -> ClickResult)? = null,
  val longClick: ((LongClickEvent) -> ClickResult)? = null,
  val hover: ((HoverEvent) -> Unit)? = null,
)
