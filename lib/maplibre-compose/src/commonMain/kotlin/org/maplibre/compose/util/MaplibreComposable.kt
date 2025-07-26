package org.maplibre.compose.util

import androidx.compose.runtime.ComposableTargetMarker

/**
 * This annotation marks a composable for use within the context of a
 * [org.maplibre.compose.map.MaplibreMap] `content`.
 */
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
