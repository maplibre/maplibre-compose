package org.maplibre.compose.util

import androidx.compose.ui.unit.DpOffset
import org.maplibre.spatialk.geojson.Position

/**
 * A callback for when the map is clicked. Called before any layer click handlers.
 *
 * The position preserves the clicked world copy: its longitude may extend past ±180° when the
 * viewport shows a repeated world, matching
 * [org.maplibre.compose.map.MapState.positionFromScreenLocation].
 *
 * @return [ClickResult.Consume] if this click should be consumed and not passed down to layers or
 *   [ClickResult.Pass] if it should be passed down.
 */
public typealias MapClickHandler = (Position, DpOffset) -> ClickResult
