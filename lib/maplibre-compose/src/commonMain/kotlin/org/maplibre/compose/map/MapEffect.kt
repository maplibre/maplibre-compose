package org.maplibre.compose.map

import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding

/**
 * A platform or Compose side effect that a [MapKernel] reduce produced. The kernel never runs these
 * while it holds the serial token: the caller executes them after the logical record is published.
 */
internal sealed interface MapEffect {
  /** Push [style] to [adapter] as kernel generation [generation]. */
  data class LoadStyle(val adapter: Any, val style: BaseStyle, val generation: Long) : MapEffect

  /** Jump [adapter] to [camera]. */
  data class SendCamera(val adapter: Any, val camera: org.maplibre.compose.camera.CameraPosition) :
    MapEffect

  /** Apply the current session options to [adapter]. */
  data class ApplySessionOptions(val adapter: Any) : MapEffect

  /** Point the style node at [binding] and request a composition apply. */
  data class PointBinding(val binding: StyleBinding) : MapEffect

  /** Refresh public style collections from the live binding. */
  data object RefreshCollections : MapEffect

  /** Drop the departed session's click and frame hooks. */
  data object ResetSessionHooks : MapEffect

  /** Drop the departed UI's composition locals. */
  data object ClearInheritedLocals : MapEffect
}
