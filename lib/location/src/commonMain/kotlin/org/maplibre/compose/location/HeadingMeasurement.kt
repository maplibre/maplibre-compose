package org.maplibre.compose.location

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Rotation

/**
 * One measured horizontal direction that a device faces.
 *
 * @property bearing Direction clockwise from [reference].
 * @property reference North reference for [bearing].
 * @property accuracy Estimated bearing error, or `null` when unknown.
 * @property measuredAt Wall-clock instant when the heading was measured.
 */
@Serializable
public data class HeadingMeasurement(
  val bearing: Bearing,
  val reference: HeadingReference,
  val accuracy: Rotation? = null,
  val measuredAt: Instant,
)

/** North reference for a [HeadingMeasurement] bearing. */
public enum class HeadingReference {
  /** Geographic true north. */
  TrueNorth,

  /** Magnetic north. */
  MagneticNorth,

  /** True north when the platform has magnetic declination, and magnetic north otherwise. */
  TrueOrMagneticNorth,
}
