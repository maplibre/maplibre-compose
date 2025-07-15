package org.maplibre.compose.style

import androidx.compose.runtime.ComposableTargetMarker
import androidx.compose.ui.unit.DpOffset
import io.github.dellisd.spatialk.geojson.Feature
import io.github.dellisd.spatialk.geojson.Position

/**
 * A callback for when the map is clicked. Called before any layer click handlers.
 *
 * @return [ClickResult.Consume] if this click should be consumed and not passed down to layers or
 *   [ClickResult.Pass] if it should be passed down.
 */
public typealias MapClickHandler = (Position, DpOffset) -> ClickResult

/**
 * A callback for when a feature is clicked.
 *
 * @return [ClickResult.Consume] if this click should be consumed and not passed down to layers
 *   rendered below this one or [ClickResult.Pass] if it should be passed down.
 */
public typealias FeaturesClickHandler = (List<Feature>) -> ClickResult

/** The result of a click event handler. See [MapClickHandler] and [FeaturesClickHandler]. */
public enum class ClickResult(internal val consumed: Boolean) {
  /** Consume the click event, preventing it from being passed down to layers below. */
  Consume(true),

  /** Pass the click event down to layers below. */
  Pass(false),
}

/** This annotation marks a composable for use within the context of a [MaplibreMap] `content`. */
@Retention(AnnotationRetention.BINARY)
@ComposableTargetMarker(description = "MapLibre Composable")
@Target(
  AnnotationTarget.FILE,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.TYPE,
  AnnotationTarget.TYPE_PARAMETER,
)
public annotation class MaplibreComposable
