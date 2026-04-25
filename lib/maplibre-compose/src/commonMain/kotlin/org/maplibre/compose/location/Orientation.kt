package org.maplibre.compose.location

import kotlin.time.TimeMark
import kotlinx.serialization.Serializable

@Serializable
public data class Orientation(val orientation: BearingWithAccuracy? = null, val timestamp: TimeMark)
