package org.maplibre.compose.demoapp

import androidx.compose.ui.unit.DpOffset
import io.github.dellisd.spatialk.geojson.Position

data class MapClickEvent(val position: Position, val offset: DpOffset)
