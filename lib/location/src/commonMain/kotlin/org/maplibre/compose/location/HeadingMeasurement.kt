package org.maplibre.compose.location

import kotlin.jvm.JvmInline
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

/**
 * North reference for a [HeadingMeasurement] bearing.
 *
 * Serializes as its name, such as `TrueNorth`, and keeps a name that this version does not define.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Serializable
@JvmInline
public value class HeadingReference private constructor(private val name: String) {
  override fun toString(): String = name

  public companion object {
    /** Geographic true north. */
    public val TrueNorth: HeadingReference = HeadingReference("TrueNorth")

    /** Magnetic north. */
    public val MagneticNorth: HeadingReference = HeadingReference("MagneticNorth")

    /** True north when the platform has magnetic declination, and magnetic north otherwise. */
    public val TrueOrMagneticNorth: HeadingReference = HeadingReference("TrueOrMagneticNorth")
  }
}
