package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.maplibre.compose.interaction.MapInteractions

/** A callback body may change; removing it retires its admitted subscription. */
internal class SubscriptionSlot {
  private var identity: Any? by mutableStateOf(null)

  fun update(present: Boolean) {
    if (!present) identity = null else if (identity == null) identity = Any()
  }

  fun capture(): Any? = identity

  fun contains(captured: Any?): Boolean = captured != null && identity === captured
}

internal class LifecycleMembership(
  private val slots: LifecycleSubscriptions,
  private val start: Any?,
  private val delta: Any?,
  private val end: Any?,
  private val cancel: Any?,
) {
  val hasStart: Boolean
    get() = slots.start.contains(start)

  val hasDelta: Boolean
    get() = slots.delta.contains(delta)

  val hasEnd: Boolean
    get() = slots.end.contains(end)

  val hasCancel: Boolean
    get() = slots.cancel.contains(cancel)
}

/** Each callback field has independent membership, including delta-only subscriptions. */
internal class LifecycleSubscriptions {
  val start = SubscriptionSlot()
  val delta = SubscriptionSlot()
  val end = SubscriptionSlot()
  val cancel = SubscriptionSlot()

  fun capture(): LifecycleMembership =
    LifecycleMembership(this, start.capture(), delta.capture(), end.capture(), cancel.capture())

  private fun update(onStart: Any?, onDelta: Any?, onEnd: Any?, onCancel: Any?) {
    start.update(onStart != null)
    delta.update(onDelta != null)
    end.update(onEnd != null)
    cancel.update(onCancel != null)
  }

  fun update(handlers: DragHandlers) =
    update(handlers.onStart, handlers.onDelta, handlers.onEnd, handlers.onCancel)

  fun update(handlers: ZoomHandlers) =
    update(handlers.onStart, handlers.onDelta, handlers.onEnd, handlers.onCancel)

  fun update(handlers: RotateHandlers) =
    update(handlers.onStart, handlers.onDelta, handlers.onEnd, handlers.onCancel)

  fun update(handlers: TiltHandlers) =
    update(handlers.onStart, handlers.onDelta, handlers.onEnd, handlers.onCancel)

  fun update(handlers: ScrollHandlers) =
    update(handlers.onStart, handlers.onDelta, handlers.onEnd, handlers.onCancel)
}

/** Updated after each committed composition, including changes between input events. */
internal class InteractionSubscriptions(initial: MapInteractions) {
  var drag = LifecycleSubscriptions()
    private set

  var tapDrag = LifecycleSubscriptions()
    private set

  var transformPan = LifecycleSubscriptions()
    private set

  var transformZoom = LifecycleSubscriptions()
    private set

  var transformRotate = LifecycleSubscriptions()
    private set

  var transformTilt = LifecycleSubscriptions()
    private set

  var scroll = LifecycleSubscriptions()
    private set

  val click = SubscriptionSlot()
  val doubleClick = SubscriptionSlot()
  val longClick = SubscriptionSlot()
  val unhandledClick = SubscriptionSlot()
  val hover = SubscriptionSlot()
  val keys = SubscriptionSlot()
  val rotary = SubscriptionSlot()

  private var structure = initial.structuralKey

  init {
    update(initial)
  }

  fun update(options: MapInteractions) {
    if (structure != options.structuralKey) {
      // Structural replacement cancels the outgoing input before applying new subscriptions.
      // Its admitted memberships retain the old slots until that cancellation is delivered.
      structure = options.structuralKey
      drag = LifecycleSubscriptions()
      tapDrag = LifecycleSubscriptions()
      transformPan = LifecycleSubscriptions()
      transformZoom = LifecycleSubscriptions()
      transformRotate = LifecycleSubscriptions()
      transformTilt = LifecycleSubscriptions()
      scroll = LifecycleSubscriptions()
    }
    val bindings = options.bindings
    drag.update(bindings.drag.handlers)
    tapDrag.update(bindings.tapDrag.handlers)
    transformPan.update(bindings.transform.pan.handlers)
    transformZoom.update(bindings.transform.zoom.handlers)
    transformRotate.update(bindings.transform.rotate.handlers)
    transformTilt.update(bindings.transform.tilt.handlers)
    scroll.update(bindings.scroll.handlers)
    click.update(options.callbacks.click != null)
    doubleClick.update(options.callbacks.doubleClick != null)
    longClick.update(options.callbacks.longClick != null)
    unhandledClick.update(options.callbacks.unhandledClick != null)
    hover.update(options.callbacks.hover != null)
    keys.update(bindings.keys.onEvent != null)
    rotary.update(bindings.rotary.onEvent != null)
  }
}
