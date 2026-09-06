package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.util.ClickResult

/** Keeps configuration blocks scoped to their current input or camera component. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class MapInteractionDsl

/**
 * Immutable camera policy, input mappings, and application callbacks for one map. Callback bodies
 * and subscription presence can update without restarting input. Changes to permissions, matching
 * patterns, or tuning cancel input using the previous configuration.
 */
@Immutable
public class MapInteractions
private constructor(
  internal val camera: CameraConfiguration,
  internal val bindings: InteractionBindings,
  internal val callbacks: InteractionCallbacks,
  public val animationDuration: Duration = 300.milliseconds,
) {
  /** Edits [from]; omitted settings inherit. Mapping blocks replace their family's table. */
  public constructor(
    from: MapInteractions = Standard,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(
    builder.cameraBuilder.build(),
    builder.bindingsBuilder.build(builder.cameraBuilder.build()),
    builder.callbacksBuilder.build(),
    builder.animationDuration,
  )

  internal val structuralKey: Any =
    listOf(camera.structuralKey, bindings.structuralKey, animationDuration)

  override fun equals(other: Any?): Boolean =
    other is MapInteractions &&
      camera == other.camera &&
      bindings == other.bindings &&
      callbacks == other.callbacks &&
      animationDuration == other.animationDuration

  override fun hashCode(): Int = listOf(camera, bindings, callbacks, animationDuration).hashCode()

  init {
    requireNonnegativeFinite(animationDuration, "animationDuration")
  }

  @MapInteractionDsl
  public class Builder internal constructor(from: MapInteractions) {
    public var animationDuration: Duration = from.animationDuration
    internal val cameraBuilder = CameraBuilder(from.camera)
    internal val bindingsBuilder = InteractionBindingsBuilder(from.bindings)
    internal val callbacksBuilder = InteractionCallbacksBuilder(from.callbacks)

    public fun camera(block: CameraBuilder.() -> Unit) {
      cameraBuilder.apply(block)
    }

    public fun bindings(block: InteractionBindingsBuilder.() -> Unit) {
      bindingsBuilder.apply(block)
    }

    public fun callbacks(block: InteractionCallbacksBuilder.() -> Unit) {
      callbacksBuilder.apply(block)
    }
  }

  public companion object {
    /** Standard navigation with no application subscriptions. */
    public val Standard: MapInteractions =
      MapInteractions(
        CameraConfiguration(),
        InteractionBindings.standard(),
        InteractionCallbacks(),
      )
    /**
     * Disables built-in input, including feature clicks and hover; external camera input remains
     * allowed.
     */
    public val None: MapInteractions =
      MapInteractions(
        CameraConfiguration(),
        InteractionBindings.none(),
        InteractionCallbacks(),
      )
  }
}

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
  val contextClick: ((ContextClickEvent) -> ClickResult)? = null,
  val twoFingerClick: ((TwoFingerTapEvent) -> ClickResult)? = null,
  val hover: ((HoverEvent) -> Unit)? = null,
)

/** Application intents run before eligible feature layers and camera fallback. */
@MapInteractionDsl
public class InteractionCallbacksBuilder
internal constructor(private var value: InteractionCallbacks) {
  public fun click(block: ClickCallbackBuilder.() -> Unit) {
    val builder = ClickCallbackBuilder(value.click, value.unhandledClick).apply(block)
    value = value.copy(click = builder.event, unhandledClick = builder.unhandled)
  }

  public fun doubleClick(block: DoubleClickCallbackBuilder.() -> Unit) {
    value =
      value.copy(doubleClick = DoubleClickCallbackBuilder(value.doubleClick).apply(block).event)
  }

  public fun contextClick(block: ContextClickCallbackBuilder.() -> Unit) {
    value =
      value.copy(contextClick = ContextClickCallbackBuilder(value.contextClick).apply(block).event)
  }

  public fun twoFingerClick(block: TwoFingerClickCallbackBuilder.() -> Unit) {
    value =
      value.copy(
        twoFingerClick = TwoFingerClickCallbackBuilder(value.twoFingerClick).apply(block).event
      )
  }

  public fun hover(block: HoverCallbackBuilder.() -> Unit) {
    value = value.copy(hover = HoverCallbackBuilder(value.hover).apply(block).event)
  }

  internal fun build(): InteractionCallbacks = value
}

@MapInteractionDsl
public class ClickCallbackBuilder
internal constructor(
  internal var event: ((TapEvent) -> ClickResult)?,
  internal var unhandled: ((TapEvent) -> ClickResult)?,
) {
  public fun onEvent(block: ((TapEvent) -> ClickResult)?) {
    event = block
  }

  public fun onUnhandled(block: ((TapEvent) -> ClickResult)?) {
    unhandled = block
  }
}

@MapInteractionDsl
public class DoubleClickCallbackBuilder
internal constructor(internal var event: ((DoubleTapEvent) -> ClickResult)?) {
  public fun onEvent(block: ((DoubleTapEvent) -> ClickResult)?) {
    event = block
  }
}

@MapInteractionDsl
public class ContextClickCallbackBuilder
internal constructor(internal var event: ((ContextClickEvent) -> ClickResult)?) {
  public fun onEvent(block: ((ContextClickEvent) -> ClickResult)?) {
    event = block
  }
}

@MapInteractionDsl
public class TwoFingerClickCallbackBuilder
internal constructor(internal var event: ((TwoFingerTapEvent) -> ClickResult)?) {
  public fun onEvent(block: ((TwoFingerTapEvent) -> ClickResult)?) {
    event = block
  }
}

@MapInteractionDsl
public class HoverCallbackBuilder
internal constructor(internal var event: ((HoverEvent) -> Unit)?) {
  public fun onEvent(block: ((HoverEvent) -> Unit)?) {
    event = block
  }
}

internal enum class DragResponse {
  Pan,
  RotateTilt,
  FitBounds,
  None,
}

internal enum class ScrollResponse {
  Pan,
  Zoom,
  None,
}

internal enum class TapResponse {
  ZoomIn,
  ZoomOut,
  None,
}

internal enum class KeyResponse {
  PanLeft,
  PanRight,
  PanUp,
  PanDown,
  ZoomIn,
  ZoomOut,
  RotateLeft,
  RotateRight,
  TiltUp,
  TiltDown,
  Engage,
  Disengage,
  Back,
  None;

  val isCamera: Boolean
    get() = this !in setOf(Engage, Disengage, Back, None)
}

internal data class DragMapping(val pattern: PointerPattern, val response: DragResponse)

internal data class ScrollMapping(
  val pattern: PointerPattern,
  val response: ScrollResponse,
)

internal data class TapMapping(val pattern: PointerPattern, val response: TapResponse)

internal data class KeyMapping(
  val key: Key?,
  val modifiers: ModifierMatch,
  val response: KeyResponse,
)

/** One response is required in every mapping row. */
@MapInteractionDsl
public class DragResponseBuilder internal constructor() {
  private var response: DragResponse? = null

  private fun select(value: DragResponse) {
    require(response == null) { "A mapping must declare exactly one response" }
    response = value
  }

  public fun pan() {
    select(DragResponse.Pan)
  }

  public fun rotateTilt() {
    select(DragResponse.RotateTilt)
  }

  public fun fitBounds() {
    select(DragResponse.FitBounds)
  }

  public fun none() {
    select(DragResponse.None)
  }

  internal fun build(): DragResponse =
    requireNotNull(response) { "A mapping must declare a response" }
}

@MapInteractionDsl
public class ScrollResponseBuilder internal constructor() {
  private var response: ScrollResponse? = null

  private fun select(value: ScrollResponse) {
    require(response == null) { "A mapping must declare exactly one response" }
    response = value
  }

  public fun pan() {
    select(ScrollResponse.Pan)
  }

  public fun zoom() {
    select(ScrollResponse.Zoom)
  }

  public fun none() {
    select(ScrollResponse.None)
  }

  internal fun build(): ScrollResponse =
    requireNotNull(response) { "A mapping must declare a response" }
}

@MapInteractionDsl
public class TapResponseBuilder internal constructor() {
  private var response: TapResponse? = null

  private fun select(value: TapResponse) {
    require(response == null) { "A mapping must declare exactly one response" }
    response = value
  }

  public fun zoomIn() {
    select(TapResponse.ZoomIn)
  }

  public fun zoomOut() {
    select(TapResponse.ZoomOut)
  }

  public fun none() {
    select(TapResponse.None)
  }

  internal fun build(): TapResponse =
    requireNotNull(response) { "A mapping must declare a response" }
}

@MapInteractionDsl
public class KeyResponseBuilder internal constructor() {
  private var response: KeyResponse? = null

  private fun select(value: KeyResponse) {
    require(response == null) { "A mapping must declare exactly one response" }
    response = value
  }

  public fun panLeft() {
    select(KeyResponse.PanLeft)
  }

  public fun panRight() {
    select(KeyResponse.PanRight)
  }

  public fun panUp() {
    select(KeyResponse.PanUp)
  }

  public fun panDown() {
    select(KeyResponse.PanDown)
  }

  public fun zoomIn() {
    select(KeyResponse.ZoomIn)
  }

  public fun zoomOut() {
    select(KeyResponse.ZoomOut)
  }

  public fun rotateLeft() {
    select(KeyResponse.RotateLeft)
  }

  public fun rotateRight() {
    select(KeyResponse.RotateRight)
  }

  public fun tiltUp() {
    select(KeyResponse.TiltUp)
  }

  public fun tiltDown() {
    select(KeyResponse.TiltDown)
  }

  public fun engage() {
    select(KeyResponse.Engage)
  }

  public fun disengage() {
    select(KeyResponse.Disengage)
  }

  public fun back() {
    select(KeyResponse.Back)
  }

  public fun none() {
    select(KeyResponse.None)
  }

  internal fun build(): KeyResponse =
    requireNotNull(response) { "A mapping must declare a response" }
}

/** Ordered mappings; the first matching permitted response wins. */
@MapInteractionDsl
public class DragMappingsBuilder internal constructor() {
  private val rows = mutableListOf<DragMapping>()
  private var hasOtherwise = false

  public fun on(
    pointerTypes: Set<PointerType>? = null,
    button: PointerButton? = null,
    modifiers: ModifierMatch = ModifierMatch.Any,
    block: DragResponseBuilder.() -> Unit,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows +=
      DragMapping(
        PointerPattern(pointerTypes?.toSet(), button, modifiers),
        DragResponseBuilder().apply(block).build(),
      )
  }

  public fun otherwise(block: DragResponseBuilder.() -> Unit) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += DragMapping(PointerPattern(), DragResponseBuilder().apply(block).build())
  }

  internal fun build(): List<DragMapping> = rows.toList()
}

/** Ordered mappings; the first matching permitted response wins. */
@MapInteractionDsl
public class ScrollMappingsBuilder internal constructor() {
  private val rows = mutableListOf<ScrollMapping>()
  private var hasOtherwise = false

  public fun on(
    pointerTypes: Set<PointerType>? = null,
    modifiers: ModifierMatch = ModifierMatch.Any,
    block: ScrollResponseBuilder.() -> Unit,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows +=
      ScrollMapping(
        PointerPattern(pointerTypes?.toSet(), modifiers = modifiers),
        ScrollResponseBuilder().apply(block).build(),
      )
  }

  public fun otherwise(block: ScrollResponseBuilder.() -> Unit) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += ScrollMapping(PointerPattern(), ScrollResponseBuilder().apply(block).build())
  }

  internal fun build(): List<ScrollMapping> = rows.toList()
}

/** Ordered mappings; the first matching permitted response wins. */
@MapInteractionDsl
public class TapMappingsBuilder internal constructor() {
  private val rows = mutableListOf<TapMapping>()
  private var hasOtherwise = false

  public fun on(
    pointerTypes: Set<PointerType>? = null,
    button: PointerButton? = null,
    modifiers: ModifierMatch = ModifierMatch.Any,
    block: TapResponseBuilder.() -> Unit,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows +=
      TapMapping(
        PointerPattern(pointerTypes?.toSet(), button, modifiers),
        TapResponseBuilder().apply(block).build(),
      )
  }

  public fun otherwise(block: TapResponseBuilder.() -> Unit) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += TapMapping(PointerPattern(), TapResponseBuilder().apply(block).build())
  }

  internal fun build(): List<TapMapping> = rows.toList()
}

/** Ordered mappings; the first matching permitted response wins. */
@MapInteractionDsl
public class KeyMappingsBuilder internal constructor() {
  private val rows = mutableListOf<KeyMapping>()
  private var hasOtherwise = false

  /** Modifiers match exactly by default, so unconfigured system shortcuts remain unclaimed. */
  public fun on(
    key: Key,
    modifiers: ModifierMatch = ModifierMatch.Exactly(),
    block: KeyResponseBuilder.() -> Unit,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows += KeyMapping(key, modifiers, KeyResponseBuilder().apply(block).build())
  }

  public fun otherwise(block: KeyResponseBuilder.() -> Unit) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += KeyMapping(null, ModifierMatch.Any, KeyResponseBuilder().apply(block).build())
  }

  internal fun build(): List<KeyMapping> = rows.toList()
}
