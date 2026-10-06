package org.maplibre.compose.location

import androidx.compose.runtime.Immutable
import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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
@Immutable
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
@Immutable
@Serializable(with = HeadingReferenceSerializer::class)
public sealed interface HeadingReference {
  /** Geographic true north. */
  public data object TrueNorth : HeadingReference

  /** Magnetic north. */
  public data object MagneticNorth : HeadingReference

  /** True north when the platform has magnetic declination, and magnetic north otherwise. */
  public data object TrueOrMagneticNorth : HeadingReference
}

/**
 * A serialized [HeadingReference] name that this version does not define. Keeps [HeadingReference]
 * open: callers' `when` needs an `else` branch.
 */
internal data class UnrecognizedHeadingReference(val name: String) : HeadingReference {
  override fun toString(): String = name
}

/** Writes a [HeadingReference] as its name, and reads a name this version does not define as is. */
internal object HeadingReferenceSerializer : KSerializer<HeadingReference> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor(
      "org.maplibre.compose.location.HeadingReference",
      PrimitiveKind.STRING,
    )

  override fun serialize(encoder: Encoder, value: HeadingReference) {
    encoder.encodeString(value.toString())
  }

  override fun deserialize(decoder: Decoder): HeadingReference =
    when (val name = decoder.decodeString()) {
      "TrueNorth" -> HeadingReference.TrueNorth
      "MagneticNorth" -> HeadingReference.MagneticNorth
      "TrueOrMagneticNorth" -> HeadingReference.TrueOrMagneticNorth
      else -> UnrecognizedHeadingReference(name)
    }
}
