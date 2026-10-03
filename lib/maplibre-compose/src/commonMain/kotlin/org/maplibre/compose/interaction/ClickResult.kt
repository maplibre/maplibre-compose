package org.maplibre.compose.interaction

/**
 * Whether map click handling continues after a map callback or [FeatureClickHandler].
 *
 * This controls delivery within the map, including feature rows and the camera response. It does
 * not control Compose pointer propagation or pass input to parent composables.
 */
public enum class ClickResult(internal val consumed: Boolean) {
  /** Stop map click handling, including the remaining rows and camera response. */
  Consume(true),

  /** Continue to the next map click handler or, if none consumes the click, the camera response. */
  Pass(false),
}
