package org.maplibre.compose.interaction

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import org.maplibre.compose.interaction.internal.GesturePointerSample
import org.maplibre.spatialk.geojson.Position

/** Identifies an action's input lifetime within one attached map input node. */
public sealed interface GestureEvent {
  public val gestureId: Long
  public val uptimeMillis: Long
}

/** Linear screen velocity in dp per second; positive X is right and positive Y is down. */
@Immutable
public data class ScreenVelocity(val xDpPerSecond: Double, val yDpPerSecond: Double) {
  public companion object {
    public val Zero: ScreenVelocity = ScreenVelocity(0.0, 0.0)
  }
}

/** Why a recognized action stopped without a normal release or momentum. */
public enum class GestureCancellationReason {
  InputConsumed,
  InputCancelled,
  BindingChanged,
  ConfigurationChanged,
  CameraTakeover,
  Detached,
}

/**
 * A map-local pointer sample in dp, before camera padding. [position] uses the viewport snapshot
 * available before the response and can be null when projection is unavailable. [buttons] contains
 * only physical buttons, including for touch and classified trackpad input.
 */
@Immutable
public abstract class PointerGestureEvent internal constructor(sample: GesturePointerSample) :
  GestureEvent {
  final override val gestureId: Long = sample.gestureId
  final override val uptimeMillis: Long = sample.uptimeMillis
  public val screenOffset: DpOffset = sample.screenOffset
  public val position: Position? = sample.position
  public val pointerTypes: Set<PointerType> = sample.pointerTypes.toSet()
  public val buttons: Set<PointerButton> = sample.buttons.toSet()
  public val modifierKeys: Set<KeyModifier> = sample.modifierKeys.toSet()
}

/**
 * The press supplied to a custom drag predicate before reserving its input lifetime.
 * [pairedSecondPress] identifies a paired second press. Declining an eligible non-mouse press
 * leaves tap-drag zoom available.
 */
@Immutable
public class PointerPressEvent
internal constructor(
  sample: GesturePointerSample,
  public val pairedSecondPress: Boolean = false,
) : PointerGestureEvent(sample)

/** An ordinary tap or primary click. */
@Immutable
public class TapEvent internal constructor(sample: GesturePointerSample) :
  PointerGestureEvent(sample)

/** A paired tap or double click. Mouse still delivers its first click eagerly. */
@Immutable
public class DoubleTapEvent internal constructor(sample: GesturePointerSample) :
  PointerGestureEvent(sample)

/** A touch long press or a secondary mouse click on release. */
@Immutable
public class LongClickEvent internal constructor(sample: GesturePointerSample) :
  PointerGestureEvent(sample)

/** A single-contact drag, pair centroid pan, or platform-recognized pan. */
@Immutable
public sealed class DragEvent private constructor(sample: GesturePointerSample) :
  PointerGestureEvent(sample) {
  /** Recognition begins at [screenOffset]; [startOffset] is the original press or anchor. */
  public class Start
  internal constructor(sample: GesturePointerSample, public val startOffset: DpOffset) :
    DragEvent(sample)

  /** Incremental displacement; the first delta contains only travel beyond recognition slop. */
  public class Delta
  internal constructor(sample: GesturePointerSample, public val delta: DpOffset) : DragEvent(sample)

  /** Input completed. Any configured momentum belongs to the same camera session. */
  public class End
  internal constructor(sample: GesturePointerSample, public val velocity: ScreenVelocity) :
    DragEvent(sample)

  /** Cancellation carries the last sample and never starts momentum. */
  public class Cancel
  internal constructor(sample: GesturePointerSample, public val reason: GestureCancellationReason) :
    DragEvent(sample)
}

/** A pair or platform-recognized scale. Scale factors are positive and multiplicative. */
@Immutable
public sealed class PinchEvent private constructor(sample: GesturePointerSample) :
  PointerGestureEvent(sample) {
  /** Recognition begins at [screenOffset]; [startOffset] is the original press or anchor. */
  public class Start
  internal constructor(sample: GesturePointerSample, public val startOffset: DpOffset) :
    PinchEvent(sample)

  /** Incremental displacement; the first delta contains only travel beyond recognition slop. */
  public class Delta
  internal constructor(sample: GesturePointerSample, public val scaleFactor: Double) :
    PinchEvent(sample)

  /** Input completed. Any configured momentum belongs to the same camera session. */
  public class End
  internal constructor(sample: GesturePointerSample, public val zoomVelocity: Double) :
    PinchEvent(sample)

  /** Cancellation carries the last sample and never starts momentum. */
  public class Cancel
  internal constructor(sample: GesturePointerSample, public val reason: GestureCancellationReason) :
    PinchEvent(sample)
}

/** A two-contact rotation. Angles and angular velocity use degrees. */
@Immutable
public sealed class RotateEvent private constructor(sample: GesturePointerSample) :
  PointerGestureEvent(sample) {
  /** Recognition begins at [screenOffset]; [startOffset] is the original press or anchor. */
  public class Start
  internal constructor(sample: GesturePointerSample, public val startOffset: DpOffset) :
    RotateEvent(sample)

  /** Incremental displacement; the first delta contains only travel beyond recognition slop. */
  public class Delta
  internal constructor(sample: GesturePointerSample, public val degrees: Double) :
    RotateEvent(sample)

  /** Input completed. Any configured momentum belongs to the same camera session. */
  public class End
  internal constructor(sample: GesturePointerSample, public val angularVelocity: Double) :
    RotateEvent(sample)

  /** Cancellation carries the last sample and never starts momentum. */
  public class Cancel
  internal constructor(sample: GesturePointerSample, public val reason: GestureCancellationReason) :
    RotateEvent(sample)
}

/** A two-contact vertical drag. The response converts dp into camera pitch. */
@Immutable
public sealed class ShoveEvent private constructor(sample: GesturePointerSample) :
  PointerGestureEvent(sample) {
  /** Recognition begins at [screenOffset]; [startOffset] is the original press or anchor. */
  public class Start
  internal constructor(sample: GesturePointerSample, public val startOffset: DpOffset) :
    ShoveEvent(sample)

  /** Incremental displacement; the first delta contains only travel beyond recognition slop. */
  public class Delta
  internal constructor(
    sample: GesturePointerSample,
    public val deltaY: androidx.compose.ui.unit.Dp,
  ) : ShoveEvent(sample)

  /** Input completed. Any configured momentum belongs to the same camera session. */
  public class End
  internal constructor(sample: GesturePointerSample, public val velocity: ScreenVelocity) :
    ShoveEvent(sample)

  /** Cancellation carries the last sample and never starts momentum. */
  public class Cancel
  internal constructor(sample: GesturePointerSample, public val reason: GestureCancellationReason) :
    ShoveEvent(sample)
}

/** A normalized scroll burst. No library momentum follows its End. */
@Immutable
public sealed class ScrollEvent private constructor(sample: GesturePointerSample) :
  PointerGestureEvent(sample) {
  /** Scrolling begins at [startOffset]. */
  public class Start
  internal constructor(sample: GesturePointerSample, public val startOffset: DpOffset) :
    ScrollEvent(sample)

  /**
   * The host scroll amount as content displacement in dp, before applying the selected response.
   */
  public class Delta
  internal constructor(
    sample: GesturePointerSample,
    public val delta: DpOffset,
  ) : ScrollEvent(sample)

  /** Scrolling ended after the configured idle duration. */
  public class End
  internal constructor(sample: GesturePointerSample, public val velocity: ScreenVelocity) :
    ScrollEvent(sample)

  /** Cancellation carries the last sample and never starts momentum. */
  public class Cancel
  internal constructor(sample: GesturePointerSample, public val reason: GestureCancellationReason) :
    ScrollEvent(sample)
}

/**
 * Mouse or stylus entry, movement, and exit while no contacts or physical buttons are pressed.
 * Camera and style changes do not produce events. Hover does not consume input or query features.
 */
@Immutable
public sealed class HoverEvent private constructor(sample: GesturePointerSample) :
  PointerGestureEvent(sample) {
  public class Enter internal constructor(sample: GesturePointerSample) : HoverEvent(sample)

  public class Move internal constructor(sample: GesturePointerSample) : HoverEvent(sample)

  public class Exit internal constructor(sample: GesturePointerSample) : HoverEvent(sample)
}

/** An accepted key press or repeat; no pointer location is fabricated. */
@Immutable
public class KeyGestureEvent
internal constructor(
  override val gestureId: Long,
  override val uptimeMillis: Long,
  public val key: Key,
  modifierKeys: Set<KeyModifier>,
  public val isRepeat: Boolean,
) : GestureEvent {
  public val modifierKeys: Set<KeyModifier> = modifierKeys.toSet()
}

/** A focused rotary sample. Positive [verticalScrollPixels] zooms out with the default response. */
@Immutable
public class RotaryGestureEvent
internal constructor(
  override val gestureId: Long,
  override val uptimeMillis: Long,
  public val verticalScrollPixels: Float,
  public val horizontalScrollPixels: Float,
) : GestureEvent
