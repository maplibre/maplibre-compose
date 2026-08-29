package org.maplibre.compose.location

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Rotation

/**
 * One measured horizontal direction that a device faces.
 *
 * @property bearing Absolute direction from north.
 * @property accuracy Estimated bearing error, or `null` when unknown.
 * @property measuredAt Wall-clock instant when the heading was measured.
 */
@Serializable
public data class Heading(
  val bearing: Bearing,
  val accuracy: Rotation? = null,
  val measuredAt: Instant,
)
