package org.maplibre.compose.interaction

import org.maplibre.compose.interaction.internal.InteractionCallbacks

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

  /** Respond to a touch long press or secondary mouse click. */
  public fun longClick(block: LongClickCallbackBuilder.() -> Unit) {
    value = value.copy(longClick = LongClickCallbackBuilder(value.longClick).apply(block).event)
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
public class LongClickCallbackBuilder
internal constructor(internal var event: ((LongClickEvent) -> ClickResult)?) {
  public fun onEvent(block: ((LongClickEvent) -> ClickResult)?) {
    event = block
  }
}
