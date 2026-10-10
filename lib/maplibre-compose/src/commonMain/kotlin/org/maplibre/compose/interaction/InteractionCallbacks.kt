package org.maplibre.compose.interaction

import org.maplibre.compose.interaction.internal.InteractionCallbacks

/**
 * Map-wide callbacks for clicks, double clicks, and long clicks. The `onEvent` callbacks run before
 * the matching handlers of layers, and returning [ClickResult.Consume] stops the click there.
 * [ClickCallbackBuilder.onUnhandled] runs after them.
 */
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
  internal var event: ((ClickEvent) -> ClickResult)?,
  internal var unhandled: ((ClickEvent) -> ClickResult)?,
) {
  /**
   * Runs for each click before any layer's `onClick` handler. Return [ClickResult.Pass] to let
   * layer handlers receive the click.
   */
  public fun onEvent(block: ((ClickEvent) -> ClickResult)?) {
    event = block
  }

  /**
   * Runs for a click that neither [onEvent] nor a layer's `onClick` handler consumed, including a
   * click where no layer with an `onClick` handler has features. Use it, for example, to clear a
   * selection when the user clicks an empty part of the map.
   */
  public fun onUnhandled(block: ((ClickEvent) -> ClickResult)?) {
    unhandled = block
  }
}

@MapInteractionDsl
public class DoubleClickCallbackBuilder
internal constructor(internal var event: ((ClickEvent) -> ClickResult)?) {
  public fun onEvent(block: ((ClickEvent) -> ClickResult)?) {
    event = block
  }
}

@MapInteractionDsl
public class LongClickCallbackBuilder
internal constructor(internal var event: ((ClickEvent) -> ClickResult)?) {
  public fun onEvent(block: ((ClickEvent) -> ClickResult)?) {
    event = block
  }
}
