package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.util.ClickResult

/**
 * Immutable input bindings for a map. The default builder edits [Standard]; use [None] for a map
 * controlled entirely by application input. Callback changes take effect during input without
 * restarting recognition; changing filters, tuning, or handler presence cancels current input.
 */
@Immutable
public class MapGestures
private constructor(
  internal val bindings: List<GestureBinding>,
  internal val keyBindings: GestureKeyBindings,
  public val scrollIdleDuration: Duration,
  public val animationDuration: Duration,
) {
  public constructor(
    from: MapGestures = Standard,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(
    builder.buildBindings(),
    builder.keyBuilder.build(),
    builder.scrollIdleDuration,
    builder.animationDuration,
  ) {
    requireNonnegativeFinite(scrollIdleDuration, "scrollIdleDuration")
    requireNonnegativeFinite(animationDuration, "animationDuration")
  }

  internal val structuralKey: Any =
    listOf(
      bindings.map { it.structuralKey },
      keyBindings.structuralKey,
      scrollIdleDuration,
      animationDuration,
    )

  internal fun binding(id: String): GestureBinding = bindings.first { it.id == id }

  internal fun enabled(id: String): Boolean =
    binding(id).let { it.enabled && it.filters.isNotEmpty() }

  override fun equals(other: Any?): Boolean =
    other is MapGestures &&
      bindings == other.bindings &&
      keyBindings == other.keyBindings &&
      scrollIdleDuration == other.scrollIdleDuration &&
      animationDuration == other.animationDuration

  override fun hashCode(): Int =
    listOf(bindings, keyBindings, scrollIdleDuration, animationDuration).hashCode()

  public class Builder internal constructor(from: MapGestures) {
    private val drafts = from.bindings.map { GestureBindingDraft(it) }.toMutableList()
    internal val keyBuilder = GestureKeysBuilder(from.keyBindings)
    public var scrollIdleDuration: Duration = from.scrollIdleDuration
    public var animationDuration: Duration = from.animationDuration

    private fun slot(id: String): GestureBindingDraft = drafts.first { it.id == id }

    public fun dragPan(block: PanGestureBuilder.() -> Unit) {
      PanGestureBuilder(slot("dragPan")).apply(block)
    }

    public fun dragRotateTilt(block: RotateTiltGestureBuilder.() -> Unit) {
      RotateTiltGestureBuilder(slot("dragRotateTilt")).apply(block)
    }

    public fun quickZoom(block: QuickZoomGestureBuilder.() -> Unit) {
      QuickZoomGestureBuilder(slot("quickZoom")).apply(block)
    }

    public fun boxZoom(block: BoxZoomGestureBuilder.() -> Unit) {
      BoxZoomGestureBuilder(slot("boxZoom")).apply(block)
    }

    public fun pinchZoom(block: PinchGestureBuilder.() -> Unit) {
      PinchGestureBuilder(slot("pinchZoom")).apply(block)
    }

    public fun twoFingerRotate(block: RotateGestureBuilder.() -> Unit) {
      RotateGestureBuilder(slot("twoFingerRotate")).apply(block)
    }

    public fun twoFingerTilt(block: ShoveGestureBuilder.() -> Unit) {
      ShoveGestureBuilder(slot("twoFingerTilt")).apply(block)
    }

    public fun scrollPan(block: ScrollGestureBuilder.() -> Unit) {
      ScrollGestureBuilder(slot("scrollPan")).apply(block)
    }

    public fun scrollZoom(block: ScrollGestureBuilder.() -> Unit) {
      ScrollGestureBuilder(slot("scrollZoom")).apply(block)
    }

    public fun ctrlScrollZoom(block: ScrollGestureBuilder.() -> Unit) {
      ScrollGestureBuilder(slot("ctrlScrollZoom")).apply(block)
    }

    public fun tap(block: TapGestureBuilder.() -> Unit) {
      TapGestureBuilder(slot("tap")).apply(block)
    }

    public fun doubleTap(block: DoubleTapGestureBuilder.() -> Unit) {
      DoubleTapGestureBuilder(slot("doubleTap")).apply(block)
    }

    public fun longPress(block: LongPressGestureBuilder.() -> Unit) {
      LongPressGestureBuilder(slot("longPress")).apply(block)
    }

    public fun twoFingerTap(block: TwoFingerTapGestureBuilder.() -> Unit) {
      TwoFingerTapGestureBuilder(slot("twoFingerTap")).apply(block)
    }

    public fun hover(block: HoverGestureBuilder.() -> Unit) {
      HoverGestureBuilder(slot("hover")).apply(block)
    }

    public fun keys(block: GestureKeysBuilder.() -> Unit) {
      keyBuilder.apply(block)
    }

    /** Prepends a custom drag. The last declared custom binding has the highest priority. */
    public fun drag(
      id: String,
      filter: PointerFilter = PointerFilter(),
      block: CustomDragGestureBuilder.() -> Unit,
    ) {
      require(id.isNotBlank()) { "A custom binding needs a nonblank ID" }
      require(drafts.none { it.id == id }) { "Duplicate or reserved gesture binding ID: $id" }
      val draft =
        GestureBindingDraft(GestureBinding(id, GestureFamily.Drag, filters = listOf(filter)))
      CustomDragGestureBuilder(draft).apply(block)
      drafts.add(0, draft)
    }

    internal fun buildBindings(): List<GestureBinding> = drafts.map { it.build() }
  }

  public companion object {
    public val Standard: MapGestures =
      MapGestures(
        standardGestureBindings(),
        GestureKeyBindings.standard(),
        200.milliseconds,
        300.milliseconds,
      )
    /** Disables every input family, including layer click and hover demand. */
    public val None: MapGestures =
      MapGestures(
        Standard.bindings.map { it.copy(enabled = false) },
        GestureKeyBindings.none(),
        200.milliseconds,
        300.milliseconds,
      )
    /** Preserves the camera target, including with asymmetric padding. */
    public val PositionLocked: MapGestures = MapGestures {
      dragPan { enabled = false }
      scrollPan { enabled = false }
      boxZoom { enabled = false }
      dragRotateTilt { anchor = GestureAnchor.CameraCenter }
      quickZoom { anchor = GestureAnchor.CameraCenter }
      pinchZoom { anchor = GestureAnchor.CameraCenter }
      twoFingerRotate { anchor = GestureAnchor.CameraCenter }
      tap { anchor = GestureAnchor.CameraCenter }
      doubleTap { anchor = GestureAnchor.CameraCenter }
      longPress { anchor = GestureAnchor.CameraCenter }
      twoFingerTap { anchor = GestureAnchor.CameraCenter }
      scrollZoom { anchor = GestureAnchor.CameraCenter }
      ctrlScrollZoom { anchor = GestureAnchor.CameraCenter }
      keys { clearPan() }
    }
    public val RotationLocked: MapGestures = MapGestures {
      dragRotateTilt { enabled = false }
      twoFingerRotate { enabled = false }
      twoFingerTilt { enabled = false }
      keys {
        clearRotate()
        clearTilt()
      }
    }
    public val ZoomOnly: MapGestures =
      MapGestures(from = PositionLocked) {
        dragRotateTilt { enabled = false }
        twoFingerRotate { enabled = false }
        twoFingerTilt { enabled = false }
        keys {
          clearRotate()
          clearTilt()
        }
      }
  }
}

/** Common pointer selection. Assigning [filter] replaces the entire OR-list in [filters]. */
public open class PointerGestureBuilder
internal constructor(internal val draft: GestureBindingDraft) {
  public var enabled: Boolean
    get() = draft.enabled
    set(value) {
      draft.enabled = value
    }

  public var filters: List<PointerFilter>
    get() = draft.filters.toList()
    set(value) {
      draft.filters = value.toList()
    }

  /** The single filter, or null for an empty or OR-list configuration. Null disables matching. */
  public var filter: PointerFilter?
    get() = draft.filters.singleOrNull()
    set(value) {
      draft.filters = listOfNotNull(value)
    }
}

/** Shared drag observation. Observers run synchronously before the selected response. */
public open class DragGestureBuilder internal constructor(draft: GestureBindingDraft) :
  PointerGestureBuilder(draft) {
  /**
   * Recognition slop in dp. Assigning it also sets [mouseStartSlop]; assign the latter separately
   * afterward to give mouse input a different threshold.
   */
  public var startSlop: Dp
    get() = draft.settings.startSlop
    set(value) {
      draft.settings = draft.settings.copy(startSlop = value, mouseStartSlop = value)
    }

  public var mouseStartSlop: Dp
    get() = draft.settings.mouseStartSlop
    set(value) {
      draft.settings = draft.settings.copy(mouseStartSlop = value)
    }

  public fun onStart(block: ((DragEvent.Start) -> Unit)?) {
    draft.handlers = draft.handlers.copy(dragStart = block)
  }

  public fun onDelta(block: ((DragEvent.Delta) -> Unit)?) {
    draft.handlers = draft.handlers.copy(dragDelta = block)
  }

  public fun onEnd(block: ((DragEvent.End) -> Unit)?) {
    draft.handlers = draft.handlers.copy(dragEnd = block)
  }

  public fun onCancel(block: ((DragEvent.Cancel) -> Unit)?) {
    draft.handlers = draft.handlers.copy(dragCancel = block)
  }
}

public class PanGestureBuilder internal constructor(draft: GestureBindingDraft) :
  DragGestureBuilder(draft) {
  public var continuation: Fling?
    get() = draft.settings.fling
    set(value) {
      draft.settings = draft.settings.copy(fling = value)
    }
}

public class RotateTiltGestureBuilder internal constructor(draft: GestureBindingDraft) :
  DragGestureBuilder(draft) {
  public var anchor: GestureAnchor
    get() = draft.settings.anchor
    set(value) {
      draft.settings = draft.settings.copy(anchor = value)
    }

  public var bearingDegreesPerDp: Double
    get() = draft.settings.bearingDegreesPerDp
    set(value) {
      draft.settings = draft.settings.copy(bearingDegreesPerDp = value)
    }

  public var pitchDegreesPerDp: Double
    get() = draft.settings.pitchDegreesPerDp
    set(value) {
      draft.settings = draft.settings.copy(pitchDegreesPerDp = value)
    }

  public var continuation: TiltContinuation?
    get() = draft.settings.tiltContinuation
    set(value) {
      draft.settings = draft.settings.copy(tiltContinuation = value)
    }
}

public class QuickZoomGestureBuilder internal constructor(draft: GestureBindingDraft) :
  DragGestureBuilder(draft) {
  public var direction: QuickZoomDirection
    get() = draft.settings.direction
    set(value) {
      draft.settings = draft.settings.copy(direction = value)
    }

  public var anchor: GestureAnchor
    get() = draft.settings.anchor
    set(value) {
      draft.settings = draft.settings.copy(anchor = value)
    }

  public var maximumZoomChange: Double
    get() = draft.settings.maximumZoomChange
    set(value) {
      draft.settings = draft.settings.copy(maximumZoomChange = value)
    }

  public var continuation: GestureVelocityContinuation?
    get() = draft.settings.velocityContinuation
    set(value) {
      draft.settings = draft.settings.copy(velocityContinuation = value)
    }
}

public class BoxZoomGestureBuilder internal constructor(draft: GestureBindingDraft) :
  DragGestureBuilder(draft) {}

/** A custom binding has one lifecycle callback, delivered before its selected camera response. */
public class CustomDragGestureBuilder internal constructor(draft: GestureBindingDraft) :
  PointerGestureBuilder(draft) {
  /**
   * Recognition slop in dp. Assigning it also sets [mouseStartSlop]; assign the latter separately
   * afterward to give mouse input a different threshold.
   */
  public var startSlop: Dp
    get() = draft.settings.startSlop
    set(value) {
      draft.settings = draft.settings.copy(startSlop = value, mouseStartSlop = value)
    }

  public var mouseStartSlop: Dp
    get() = draft.settings.mouseStartSlop
    set(value) {
      draft.settings = draft.settings.copy(mouseStartSlop = value)
    }

  public var action: DragAction
    get() = draft.settings.dragAction
    set(value) {
      draft.settings = draft.settings.copy(dragAction = value)
    }

  /**
   * Observes this binding; with [DragAction.Custom], this callback also owns the application
   * response.
   */
  public fun onEvent(block: ((DragEvent) -> Unit)?) {
    draft.handlers = draft.handlers.copy(dragEvent = block)
  }

  public var anchor: GestureAnchor
    get() = draft.settings.anchor
    set(value) {
      draft.settings = draft.settings.copy(anchor = value)
    }

  public fun canStart(block: ((PointerPressEvent) -> Boolean)?) {
    draft.handlers = draft.handlers.copy(canStart = block)
  }
}

public class PinchGestureBuilder internal constructor(draft: GestureBindingDraft) :
  PointerGestureBuilder(draft) {
  public var startSpanSlop: Dp
    get() = draft.settings.startSpanSlop
    set(value) {
      draft.settings = draft.settings.copy(startSpanSlop = value)
    }

  public var anchor: GestureAnchor
    get() = draft.settings.anchor
    set(value) {
      draft.settings = draft.settings.copy(anchor = value)
    }

  public var zoomScale: Double
    get() = draft.settings.zoomScale
    set(value) {
      draft.settings = draft.settings.copy(zoomScale = value)
    }

  public var continuation: GestureVelocityContinuation?
    get() = draft.settings.velocityContinuation
    set(value) {
      draft.settings = draft.settings.copy(velocityContinuation = value)
    }

  public fun onStart(block: ((PinchEvent.Start) -> Unit)?) {
    draft.handlers = draft.handlers.copy(pinchStart = block)
  }

  public fun onDelta(block: ((PinchEvent.Delta) -> Unit)?) {
    draft.handlers = draft.handlers.copy(pinchDelta = block)
  }

  public fun onEnd(block: ((PinchEvent.End) -> Unit)?) {
    draft.handlers = draft.handlers.copy(pinchEnd = block)
  }

  public fun onCancel(block: ((PinchEvent.Cancel) -> Unit)?) {
    draft.handlers = draft.handlers.copy(pinchCancel = block)
  }
}

public class RotateGestureBuilder internal constructor(draft: GestureBindingDraft) :
  PointerGestureBuilder(draft) {
  public var startAngle: Double
    get() = draft.settings.startAngle
    set(value) {
      draft.settings = draft.settings.copy(startAngle = value)
    }

  public var anchor: GestureAnchor
    get() = draft.settings.anchor
    set(value) {
      draft.settings = draft.settings.copy(anchor = value)
    }

  public var rotationScale: Double
    get() = draft.settings.rotationScale
    set(value) {
      draft.settings = draft.settings.copy(rotationScale = value)
    }

  public var continuation: GestureVelocityContinuation?
    get() = draft.settings.velocityContinuation
    set(value) {
      draft.settings = draft.settings.copy(velocityContinuation = value)
    }

  public fun onStart(block: ((RotateEvent.Start) -> Unit)?) {
    draft.handlers = draft.handlers.copy(rotateStart = block)
  }

  public fun onDelta(block: ((RotateEvent.Delta) -> Unit)?) {
    draft.handlers = draft.handlers.copy(rotateDelta = block)
  }

  public fun onEnd(block: ((RotateEvent.End) -> Unit)?) {
    draft.handlers = draft.handlers.copy(rotateEnd = block)
  }

  public fun onCancel(block: ((RotateEvent.Cancel) -> Unit)?) {
    draft.handlers = draft.handlers.copy(rotateCancel = block)
  }
}

public class ShoveGestureBuilder internal constructor(draft: GestureBindingDraft) :
  PointerGestureBuilder(draft) {
  public var startSlop: Dp
    get() = draft.settings.startSlop
    set(value) {
      draft.settings = draft.settings.copy(startSlop = value)
    }

  public var pitchDegreesPerDp: Double
    get() = draft.settings.pitchDegreesPerDp
    set(value) {
      draft.settings = draft.settings.copy(pitchDegreesPerDp = value)
    }

  public var continuation: TiltContinuation?
    get() = draft.settings.tiltContinuation
    set(value) {
      draft.settings = draft.settings.copy(tiltContinuation = value)
    }

  public fun onStart(block: ((ShoveEvent.Start) -> Unit)?) {
    draft.handlers = draft.handlers.copy(shoveStart = block)
  }

  public fun onDelta(block: ((ShoveEvent.Delta) -> Unit)?) {
    draft.handlers = draft.handlers.copy(shoveDelta = block)
  }

  public fun onEnd(block: ((ShoveEvent.End) -> Unit)?) {
    draft.handlers = draft.handlers.copy(shoveEnd = block)
  }

  public fun onCancel(block: ((ShoveEvent.Cancel) -> Unit)?) {
    draft.handlers = draft.handlers.copy(shoveCancel = block)
  }
}

public class ScrollGestureBuilder internal constructor(draft: GestureBindingDraft) :
  PointerGestureBuilder(draft) {
  public var anchor: GestureAnchor
    get() = draft.settings.anchor
    set(value) {
      draft.settings = draft.settings.copy(anchor = value)
    }

  public var zoomStep: Double
    get() = draft.settings.zoomStep
    set(value) {
      draft.settings = draft.settings.copy(zoomStep = value)
    }

  public fun onStart(block: ((ScrollEvent.Start) -> Unit)?) {
    draft.handlers = draft.handlers.copy(scrollStart = block)
  }

  public fun onDelta(block: ((ScrollEvent.Delta) -> Unit)?) {
    draft.handlers = draft.handlers.copy(scrollDelta = block)
  }

  public fun onEnd(block: ((ScrollEvent.End) -> Unit)?) {
    draft.handlers = draft.handlers.copy(scrollEnd = block)
  }

  public fun onCancel(block: ((ScrollEvent.Cancel) -> Unit)?) {
    draft.handlers = draft.handlers.copy(scrollCancel = block)
  }

  /** Accepted estimates; an empty set removes participation. */
  public var kinds: Set<ScrollKind>
    get() = draft.settings.scrollKinds.toSet()
    set(value) {
      draft.settings = draft.settings.copy(scrollKinds = value.toSet())
    }
}

public class TapGestureBuilder internal constructor(draft: GestureBindingDraft) :
  PointerGestureBuilder(draft) {
  public var cameraAction: TapCameraAction?
    get() = draft.settings.tapAction
    set(value) {
      draft.settings = draft.settings.copy(tapAction = value)
    }

  public var anchor: GestureAnchor
    get() = draft.settings.anchor
    set(value) {
      draft.settings = draft.settings.copy(anchor = value)
    }

  public var zoomStep: Double
    get() = draft.settings.zoomStep
    set(value) {
      draft.settings = draft.settings.copy(zoomStep = value)
    }

  public fun onEvent(block: ((TapEvent) -> ClickResult)?) {
    draft.handlers = draft.handlers.copy(tap = block)
  }

  /** Called only after the map handler and every interactive layer pass this tap. */
  public fun onUnhandled(block: ((TapEvent) -> ClickResult)?) {
    draft.handlers = draft.handlers.copy(unhandledTap = block)
  }
}

public class DoubleTapGestureBuilder internal constructor(draft: GestureBindingDraft) :
  PointerGestureBuilder(draft) {
  public var cameraAction: TapCameraAction?
    get() = draft.settings.tapAction
    set(value) {
      draft.settings = draft.settings.copy(tapAction = value)
    }

  public var anchor: GestureAnchor
    get() = draft.settings.anchor
    set(value) {
      draft.settings = draft.settings.copy(anchor = value)
    }

  public var zoomStep: Double
    get() = draft.settings.zoomStep
    set(value) {
      draft.settings = draft.settings.copy(zoomStep = value)
    }

  public fun onEvent(block: ((DoubleTapEvent) -> ClickResult)?) {
    draft.handlers = draft.handlers.copy(doubleTap = block)
  }
}

public class LongPressGestureBuilder internal constructor(draft: GestureBindingDraft) :
  PointerGestureBuilder(draft) {
  public var cameraAction: TapCameraAction?
    get() = draft.settings.tapAction
    set(value) {
      draft.settings = draft.settings.copy(tapAction = value)
    }

  public var anchor: GestureAnchor
    get() = draft.settings.anchor
    set(value) {
      draft.settings = draft.settings.copy(anchor = value)
    }

  public var zoomStep: Double
    get() = draft.settings.zoomStep
    set(value) {
      draft.settings = draft.settings.copy(zoomStep = value)
    }

  public fun onEvent(block: ((LongPressEvent) -> ClickResult)?) {
    draft.handlers = draft.handlers.copy(longPress = block)
  }
}

public class TwoFingerTapGestureBuilder internal constructor(draft: GestureBindingDraft) :
  PointerGestureBuilder(draft) {
  public var cameraAction: TapCameraAction?
    get() = draft.settings.tapAction
    set(value) {
      draft.settings = draft.settings.copy(tapAction = value)
    }

  public var anchor: GestureAnchor
    get() = draft.settings.anchor
    set(value) {
      draft.settings = draft.settings.copy(anchor = value)
    }

  public var zoomStep: Double
    get() = draft.settings.zoomStep
    set(value) {
      draft.settings = draft.settings.copy(zoomStep = value)
    }

  public fun onEvent(block: ((TwoFingerTapEvent) -> ClickResult)?) {
    draft.handlers = draft.handlers.copy(twoFingerTap = block)
  }
}

public class HoverGestureBuilder internal constructor(draft: GestureBindingDraft) :
  PointerGestureBuilder(draft) {
  public fun onEvent(block: ((HoverEvent) -> Unit)?) {
    draft.handlers = draft.handlers.copy(hover = block)
  }
}

internal enum class GestureFamily {
  Drag,
  Pinch,
  Rotate,
  Shove,
  Scroll,
  Tap,
  DoubleTap,
  LongPress,
  TwoFingerTap,
  Hover,
}

internal data class GestureBinding(
  val id: String,
  val family: GestureFamily,
  val enabled: Boolean = true,
  val filters: List<PointerFilter> = listOf(PointerFilter()),
  val settings: GestureBindingSettings = GestureBindingSettings(),
  val handlers: GestureBindingHandlers = GestureBindingHandlers(),
) {
  val structuralKey: Any
    get() = listOf(id, family, enabled, filters, settings, handlers.presence)
}

internal class GestureBindingDraft(binding: GestureBinding) {
  val id = binding.id
  val family = binding.family
  var enabled = binding.enabled
  var filters = binding.filters.toList()
  var settings = binding.settings
  var handlers = binding.handlers

  fun build(): GestureBinding {
    settings.validate()
    return GestureBinding(
      id,
      family,
      enabled,
      filters.toList(),
      settings.copy(scrollKinds = settings.scrollKinds.toSet()),
      handlers,
    )
  }
}

internal data class GestureBindingSettings(
  val dragAction: DragAction = DragAction.Pan,
  val startSlop: Dp = 4.dp,
  val mouseStartSlop: Dp = 3.dp,
  val startSpanSlop: Dp = 7.dp,
  val startAngle: Double = 3.0,
  val anchor: GestureAnchor = GestureAnchor.Input,
  val bearingDegreesPerDp: Double = 0.8,
  val pitchDegreesPerDp: Double = -0.5,
  val zoomScale: Double = 1.0,
  val rotationScale: Double = 1.0,
  val maximumZoomChange: Double = 4.0,
  val direction: QuickZoomDirection = QuickZoomDirection.DownZoomsIn,
  val zoomStep: Double = 1.0,
  val fling: Fling? = Fling(),
  val velocityContinuation: GestureVelocityContinuation? = GestureVelocityContinuation(),
  val tiltContinuation: TiltContinuation? = TiltContinuation(),
  val tapAction: TapCameraAction? = null,
  val scrollKinds: Set<ScrollKind> = ScrollKind.entries.toSet(),
) {
  fun validate() {
    requireNonnegativeFinite(startSlop.value.toDouble(), "startSlop")
    requireNonnegativeFinite(mouseStartSlop.value.toDouble(), "mouseStartSlop")
    requireNonnegativeFinite(startSpanSlop.value.toDouble(), "startSpanSlop")
    requireNonnegativeFinite(startAngle, "startAngle")
    listOf(
        bearingDegreesPerDp,
        pitchDegreesPerDp,
        zoomScale,
        rotationScale,
        maximumZoomChange,
        zoomStep,
      )
      .forEach {
        require(it.isFinite()) { "Gesture response sensitivities must be finite" }
      }
  }
}

internal data class GestureBindingHandlers(
  val dragEvent: ((DragEvent) -> Unit)? = null,
  val dragStart: ((DragEvent.Start) -> Unit)? = null,
  val dragDelta: ((DragEvent.Delta) -> Unit)? = null,
  val dragEnd: ((DragEvent.End) -> Unit)? = null,
  val dragCancel: ((DragEvent.Cancel) -> Unit)? = null,
  val pinchStart: ((PinchEvent.Start) -> Unit)? = null,
  val pinchDelta: ((PinchEvent.Delta) -> Unit)? = null,
  val pinchEnd: ((PinchEvent.End) -> Unit)? = null,
  val pinchCancel: ((PinchEvent.Cancel) -> Unit)? = null,
  val rotateStart: ((RotateEvent.Start) -> Unit)? = null,
  val rotateDelta: ((RotateEvent.Delta) -> Unit)? = null,
  val rotateEnd: ((RotateEvent.End) -> Unit)? = null,
  val rotateCancel: ((RotateEvent.Cancel) -> Unit)? = null,
  val shoveStart: ((ShoveEvent.Start) -> Unit)? = null,
  val shoveDelta: ((ShoveEvent.Delta) -> Unit)? = null,
  val shoveEnd: ((ShoveEvent.End) -> Unit)? = null,
  val shoveCancel: ((ShoveEvent.Cancel) -> Unit)? = null,
  val scrollStart: ((ScrollEvent.Start) -> Unit)? = null,
  val scrollDelta: ((ScrollEvent.Delta) -> Unit)? = null,
  val scrollEnd: ((ScrollEvent.End) -> Unit)? = null,
  val scrollCancel: ((ScrollEvent.Cancel) -> Unit)? = null,
  val tap: ((TapEvent) -> ClickResult)? = null,
  val unhandledTap: ((TapEvent) -> ClickResult)? = null,
  val doubleTap: ((DoubleTapEvent) -> ClickResult)? = null,
  val longPress: ((LongPressEvent) -> ClickResult)? = null,
  val twoFingerTap: ((TwoFingerTapEvent) -> ClickResult)? = null,
  val hover: ((HoverEvent) -> Unit)? = null,
  val canStart: ((PointerPressEvent) -> Boolean)? = null,
) {
  val presence: List<Boolean>
    get() =
      listOf(
          dragEvent,
          dragStart,
          dragDelta,
          dragEnd,
          dragCancel,
          pinchStart,
          pinchDelta,
          pinchEnd,
          pinchCancel,
          rotateStart,
          rotateDelta,
          rotateEnd,
          rotateCancel,
          shoveStart,
          shoveDelta,
          shoveEnd,
          shoveCancel,
          scrollStart,
          scrollDelta,
          scrollEnd,
          scrollCancel,
          tap,
          unhandledTap,
          doubleTap,
          longPress,
          twoFingerTap,
          hover,
          canStart,
        )
        .map { it != null }
}

private fun standardGestureBindings(): List<GestureBinding> {
  val mouse = setOf(PointerType.Mouse)
  val touch = setOf(PointerType.Touch, PointerType.Stylus, PointerType.Eraser)
  val primary = listOf(PointerFilter())
  fun binding(
    id: String,
    family: GestureFamily,
    filters: List<PointerFilter> = primary,
    settings: GestureBindingSettings = GestureBindingSettings(),
  ) = GestureBinding(id, family, filters = filters, settings = settings)
  return listOf(
    binding(
      "quickZoom",
      GestureFamily.Drag,
      listOf(PointerFilter(touch)),
      GestureBindingSettings(
        dragAction = DragAction.Zoom,
        startSlop = 7.dp,
        anchor = GestureAnchor.CameraCenter,
      ),
    ),
    binding(
      "dragRotateTilt",
      GestureFamily.Drag,
      listOf(
        PointerFilter(mouse, PointerButton.Secondary),
        PointerFilter(mouse, modifiers = ModifierFilter.Containing(KeyModifier.Ctrl)),
      ),
      GestureBindingSettings(dragAction = DragAction.RotateTilt, startSlop = 3.dp),
    ),
    binding(
      "boxZoom",
      GestureFamily.Drag,
      listOf(PointerFilter(mouse, modifiers = ModifierFilter.Containing(KeyModifier.Shift))),
      GestureBindingSettings(dragAction = DragAction.BoxZoom, startSlop = 3.dp),
    ),
    binding("dragPan", GestureFamily.Drag),
    binding("pinchZoom", GestureFamily.Pinch),
    binding("twoFingerRotate", GestureFamily.Rotate),
    binding(
      "twoFingerTilt",
      GestureFamily.Shove,
      settings = GestureBindingSettings(startSlop = 16.dp, pitchDegreesPerDp = -0.1),
    ),
    binding(
      "ctrlScrollZoom",
      GestureFamily.Scroll,
      listOf(PointerFilter(button = null, modifiers = ModifierFilter.Containing(KeyModifier.Ctrl))),
      GestureBindingSettings(dragAction = DragAction.Zoom, zoomStep = 0.15),
    ),
    binding(
      "scrollPan",
      GestureFamily.Scroll,
      listOf(PointerFilter(button = null)),
      GestureBindingSettings(scrollKinds = setOf(ScrollKind.Continuous), zoomStep = 0.15),
    ),
    binding(
      "scrollZoom",
      GestureFamily.Scroll,
      listOf(PointerFilter(button = null)),
      GestureBindingSettings(dragAction = DragAction.Zoom, zoomStep = 0.15),
    ),
    binding("tap", GestureFamily.Tap),
    binding(
      "doubleTap",
      GestureFamily.DoubleTap,
      settings = GestureBindingSettings(tapAction = TapCameraAction.ZoomIn),
    ),
    binding(
      "longPress",
      GestureFamily.LongPress,
      listOf(PointerFilter(touch), PointerFilter(mouse, PointerButton.Secondary)),
    ),
    binding(
      "twoFingerTap",
      GestureFamily.TwoFingerTap,
      settings = GestureBindingSettings(tapAction = TapCameraAction.ZoomOut),
    ),
    binding(
      "hover",
      GestureFamily.Hover,
      listOf(
        PointerFilter(
          setOf(PointerType.Mouse, PointerType.Stylus, PointerType.Eraser),
          button = null,
        )
      ),
    ),
  )
}
