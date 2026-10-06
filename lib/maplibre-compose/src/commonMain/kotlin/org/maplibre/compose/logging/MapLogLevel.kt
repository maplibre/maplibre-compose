package org.maplibre.compose.logging

import kotlin.jvm.JvmInline

/**
 * Severity of a [MapLogRecord]. Levels compare in ascending order of severity, so a logger can
 * filter with `level >= MapLogLevel.Warning`.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class MapLogLevel private constructor(private val severity: Int) :
  Comparable<MapLogLevel> {
  override fun compareTo(other: MapLogLevel): Int = severity.compareTo(other.severity)

  override fun toString(): String =
    when (this) {
      Debug -> "Debug"
      Info -> "Info"
      Warning -> "Warning"
      Error -> "Error"
      else -> "MapLogLevel($severity)"
    }

  public companion object {
    public val Debug: MapLogLevel = MapLogLevel(10)
    public val Info: MapLogLevel = MapLogLevel(20)
    public val Warning: MapLogLevel = MapLogLevel(30)
    public val Error: MapLogLevel = MapLogLevel(40)
  }
}
