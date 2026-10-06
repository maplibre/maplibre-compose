package org.maplibre.compose.logging

import androidx.compose.runtime.Immutable

/**
 * Severity of a [MapLogRecord]. Levels compare in ascending order of severity, so a logger can
 * filter with `level >= MapLogLevel.Warning`.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed class MapLogLevel(internal val severity: Int) : Comparable<MapLogLevel> {
  final override fun compareTo(other: MapLogLevel): Int = severity.compareTo(other.severity)

  /** The least severe level: detail for debugging. */
  public data object Debug : MapLogLevel(10)

  /** Routine information. */
  public data object Info : MapLogLevel(20)

  /** A problem that may need attention. */
  public data object Warning : MapLogLevel(30)

  /** The most severe level: a failure. */
  public data object Error : MapLogLevel(40)
}

/**
 * Keeps [MapLogLevel] open: callers' `when` needs an `else` branch. The library never reports it.
 */
internal data object UnspecifiedMapLogLevel : MapLogLevel(0)
