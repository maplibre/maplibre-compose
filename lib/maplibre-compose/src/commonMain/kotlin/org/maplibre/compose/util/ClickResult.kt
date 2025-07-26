package org.maplibre.compose.util

/** The result of a click event handler. See [MapClickHandler] and [FeaturesClickHandler]. */
public enum class ClickResult(internal val consumed: Boolean) {
  /** Consume the click event, preventing it from being passed down to layers below. */
  Consume(true),

  /** Pass the click event down to layers below. */
  Pass(false),
}
