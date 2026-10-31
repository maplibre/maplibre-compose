package org.maplibre.compose.interaction

import org.maplibre.compose.interaction.internal.InteractionCallbacks

/**
 * Map-wide callbacks for clicks, double clicks, and long clicks, and the map's feature rows.
 *
 * For each kind of click, `onEvent` runs first. Feature rows with hits run next, front to back, and
 * then `onUnhandled`. Returning [ClickResult.Consume] from any of them stops the remaining ones and
 * the camera response, such as double-tap zoom.
 */
@MapInteractionDsl
public class InteractionCallbacksBuilder
internal constructor(private var value: InteractionCallbacks) {
  public fun click(block: ClickCallbackBuilder.() -> Unit) {
    val builder = ClickCallbackBuilder(value.click, value.unhandledClick).apply(block)
    value = value.copy(click = builder.event, unhandledClick = builder.unhandled)
  }

  public fun doubleClick(block: DoubleClickCallbackBuilder.() -> Unit) {
    val builder =
      DoubleClickCallbackBuilder(value.doubleClick, value.unhandledDoubleClick).apply(block)
    value = value.copy(doubleClick = builder.event, unhandledDoubleClick = builder.unhandled)
  }

  /** Respond to a touch long press or secondary mouse click. */
  public fun longClick(block: LongClickCallbackBuilder.() -> Unit) {
    val builder = LongClickCallbackBuilder(value.longClick, value.unhandledLongClick).apply(block)
    value = value.copy(longClick = builder.event, unhandledLongClick = builder.unhandled)
  }

  /**
   * Replaces the feature rows, which respond to clicks on features of any layer in the style,
   * including base-style layers. Without this block, the rows of `from` are kept.
   */
  public fun features(block: FeatureRowsBuilder.() -> Unit) {
    value = value.copy(features = FeatureRowsBuilder().apply(block).rows.toList())
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
   * Runs for each click before any feature row. Return [ClickResult.Pass] to let feature rows
   * receive the click.
   */
  public fun onEvent(block: ((ClickEvent) -> ClickResult)?) {
    event = block
  }

  /**
   * Runs for a click that neither [onEvent] nor a feature row consumed, including a click where no
   * feature row has hits. Use it, for example, to clear a selection when the user clicks an empty
   * part of the map.
   */
  public fun onUnhandled(block: ((ClickEvent) -> ClickResult)?) {
    unhandled = block
  }
}

@MapInteractionDsl
public class DoubleClickCallbackBuilder
internal constructor(
  internal var event: ((ClickEvent) -> ClickResult)?,
  internal var unhandled: ((ClickEvent) -> ClickResult)?,
) {
  /** Runs for each double click before any feature row. */
  public fun onEvent(block: ((ClickEvent) -> ClickResult)?) {
    event = block
  }

  /** Runs for a double click that neither [onEvent] nor a feature row consumed. */
  public fun onUnhandled(block: ((ClickEvent) -> ClickResult)?) {
    unhandled = block
  }
}

@MapInteractionDsl
public class LongClickCallbackBuilder
internal constructor(
  internal var event: ((ClickEvent) -> ClickResult)?,
  internal var unhandled: ((ClickEvent) -> ClickResult)?,
) {
  /** Runs for each long click before any feature row. */
  public fun onEvent(block: ((ClickEvent) -> ClickResult)?) {
    event = block
  }

  /** Runs for a long click that neither [onEvent] nor a feature row consumed. */
  public fun onUnhandled(block: ((ClickEvent) -> ClickResult)?) {
    unhandled = block
  }
}
