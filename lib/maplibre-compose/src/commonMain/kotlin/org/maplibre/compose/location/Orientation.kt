package org.maplibre.compose.location

import kotlin.time.TimeMark
import kotlinx.serialization.Serializable

@Serializable
public data class Orientation(val orientation: BearingMeasurement? = null, val timestamp: TimeMark)
