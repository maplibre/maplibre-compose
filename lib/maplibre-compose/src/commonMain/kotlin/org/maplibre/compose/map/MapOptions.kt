package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * Configures rendering, gestures, and tile cover.
 *
 * Each field's companion object provides presets. [GestureOptions] is the same on every platform.
 * [RenderOptions] and [TileLodOptions] still differ by backend, so a custom configuration of those
 * two is written in expect/actual code.
 */
@Immutable
public data class MapOptions(
  val renderOptions: RenderOptions = RenderOptions.Standard,
  val gestureOptions: GestureOptions = GestureOptions.Standard,
  val tileLodOptions: TileLodOptions = TileLodOptions.Standard,
)
