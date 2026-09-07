package org.maplibre.compose.interaction

import androidx.compose.runtime.Immutable
import kotlin.math.abs

/** An immutable set of bearings in degrees clockwise from north. */
@Immutable
public class BearingTargets private constructor(internal val bearings: List<Double>) {
  override fun equals(other: Any?): Boolean = other is BearingTargets && bearings == other.bearings

  override fun hashCode(): Int = bearings.hashCode()

  internal fun nearestDelta(bearing: Double): Double =
    bearings.map { bearingDelta(bearing, it) }.minBy { abs(it) }

  public companion object {
    /** Finite bearings, normalized to [0, 360). Duplicate bearings are combined. */
    public fun at(vararg bearings: Double): BearingTargets {
      require(bearings.isNotEmpty()) { "At least one bearing is required" }
      require(bearings.all { it.isFinite() }) { "Bearings must be finite" }
      return BearingTargets(bearings.map(::normalizeBearing).distinct().sorted())
    }

    /** [count] equally spaced bearings, starting at [offset] degrees clockwise from north. */
    public fun evenlySpaced(count: Int, offset: Double = 0.0): BearingTargets {
      require(count > 0) { "count must be positive" }
      require(offset.isFinite()) { "offset must be finite" }
      val start = normalizeBearing(offset)
      return BearingTargets(List(count) { normalizeBearing(start + it * (360.0 / count)) }.sorted())
    }
  }
}

internal fun normalizeBearing(bearing: Double): Double = ((bearing % 360.0) + 360.0) % 360.0

internal fun bearingDelta(from: Double, to: Double): Double =
  normalizeBearing(normalizeBearing(to) - normalizeBearing(from) + 180.0) - 180.0
