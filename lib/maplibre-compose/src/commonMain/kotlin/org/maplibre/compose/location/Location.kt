package org.maplibre.compose.location

import io.github.dellisd.spatialk.geojson.Position
import kotlin.time.TimeMark

public data class Location(
  val position: Position,
  val accuracy: Double,
  val bearing: Double?,
  val bearingAccuracy: Double?,
  val timestamp: TimeMark,
)
