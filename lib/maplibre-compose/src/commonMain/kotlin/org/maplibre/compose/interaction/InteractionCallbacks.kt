package org.maplibre.compose.interaction

import org.maplibre.compose.interaction.internal.InteractionCallbacks

/**
 * For each click family, `onEvent` runs before feature rows and `onUnhandled` runs after rows with
 * hits have all passed. Returning [ClickResult.Consume] stops remaining handlers and camera
 * fallback.
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

  /** Replaces the feature rows; omitting this block inherits the rows from `from`. */
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
  public fun onEvent(block: ((ClickEvent) -> ClickResult)?) {
    event = block
  }

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
  public fun onEvent(block: ((ClickEvent) -> ClickResult)?) {
    event = block
  }

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
  public fun onEvent(block: ((ClickEvent) -> ClickResult)?) {
    event = block
  }

  public fun onUnhandled(block: ((ClickEvent) -> ClickResult)?) {
    unhandled = block
  }
}
