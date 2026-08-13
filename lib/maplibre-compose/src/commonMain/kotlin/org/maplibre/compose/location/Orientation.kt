package org.maplibre.compose.location

import kotlin.time.TimeMark
import kotlinx.serialization.Serializable

/**
 * A device-heading measurement.
 *
 * @property orientation Direction the device is pointing and its accuracy, or `null` when the
 *   provider cannot determine a heading.
 * @property timestamp Monotonic point in time when the heading was acquired.
 */
@Serializable
public data class Orientation(val orientation: BearingWithAccuracy? = null, val timestamp: TimeMark)
