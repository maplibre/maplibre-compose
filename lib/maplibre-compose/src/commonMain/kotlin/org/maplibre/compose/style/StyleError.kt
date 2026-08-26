package org.maplibre.compose.style

/**
 * A failure in the style composition or in applying its changes that the map survived.
 *
 * [org.maplibre.compose.map.MapState.styleErrors] delivers these. [message] names the phase that
 * failed, and [cause] is the failure itself.
 */
public class StyleError
internal constructor(public val message: String, public val cause: Throwable) {
  override fun toString(): String = "StyleError(message=$message, cause=$cause)"
}
