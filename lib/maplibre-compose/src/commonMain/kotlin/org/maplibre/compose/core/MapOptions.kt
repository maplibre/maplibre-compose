package org.maplibre.compose.core

/**
 * Configures various platform-specific behaviors of the map.
 *
 * Each field provides some presets available from common code, but fine-grained customization on
 * multiple platforms requires configuring these options in expect/actual code.
 */
public data class MapOptions(
  val renderOptions: RenderOptions = RenderOptions.Standard,
  val gestureOptions: GestureOptions = GestureOptions.Standard,
  val ornamentOptions: OrnamentOptions = OrnamentOptions.AllEnabled,
)
