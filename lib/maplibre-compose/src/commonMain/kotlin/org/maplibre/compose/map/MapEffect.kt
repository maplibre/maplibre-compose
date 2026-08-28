package org.maplibre.compose.map

import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding

/**
 * A platform or Compose side effect that a [MapKernel] reduce produced. The kernel never runs these
 * while it holds the serial token: the caller executes them after the logical record is published.
 */
internal sealed interface MapEffect {
  /** Push [style] to [adapter], the current style source. */
  data class LoadStyle(val adapter: Any, val style: BaseStyle) : MapEffect

  /** Jump [adapter] to [camera]. */
  data class SendCamera(val adapter: Any, val camera: org.maplibre.compose.camera.CameraPosition) :
    MapEffect

  /** Apply the current session options to [adapter]. */
  data class ApplySessionOptions(val adapter: Any) : MapEffect

  /** Point the style node at [binding] and request a composition apply. */
  data class PointBinding(val binding: StyleBinding) : MapEffect

  /** Refresh public style collections from the live binding. */
  data object RefreshCollections : MapEffect

  /** Invoke the attached session's load-finished hook. */
  data object InvokeLoadFinished : MapEffect

  /** Invoke the attached session's load-failed hook. */
  data class InvokeLoadFailed(val reason: String?) : MapEffect

  /** Drop the departed session's click and load hooks. */
  data object ResetSessionHooks : MapEffect

  /** Drop the departed UI's composition locals. */
  data object ClearInheritedLocals : MapEffect
}
