package org.maplibre.compose.material3.util

@Suppress("NOTHING_TO_INLINE")
internal object AngleMath {
  /**
   * The **least positive residue**, or the remainder of Euclidean division.
   *
   * [Double.rem] is the remainder of truncated division; [Double.mod] is of floored division.
   * Kotlin provides no built-in Euclidean division remainder function.
   */
  inline fun Double.leastPositiveResidue(other: Double) = ((this % other) + other) % other

  /** Wrap an angle to [0,360). */
  inline fun Double.wrap() = leastPositiveResidue(360.0)

  /** Get the size of the smallest angle between two angles. */
  inline fun Double.diff(other: Double) = -((other - this) + 180.0).wrap() + 180.0
}
