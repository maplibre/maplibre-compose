package org.maplibre.compose.logging

/**
 * Severity of a [MapLogRecord]. Levels compare in ascending order of severity, so a logger can
 * filter with `level >= MapLogLevel.Warning`.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface MapLogLevel : Comparable<MapLogLevel> {
  override fun compareTo(other: MapLogLevel): Int = severity.compareTo(other.severity)

  /** The least severe level: detail for debugging. */
  public data object Debug : MapLogLevel

  /** Routine information. */
  public data object Info : MapLogLevel

  /** A problem that may need attention. */
  public data object Warning : MapLogLevel

  /** The most severe level: a failure. */
  public data object Error : MapLogLevel
}

/**
 * Keeps [MapLogLevel] open: callers' `when` needs an `else` branch. The library never reports it.
 */
internal data object UnspecifiedMapLogLevel : MapLogLevel

/** Orders levels for [MapLogLevel.compareTo], leaving room for levels between the named ones. */
private val MapLogLevel.severity: Int
  get() =
    when (this) {
      MapLogLevel.Debug -> 10
      MapLogLevel.Info -> 20
      MapLogLevel.Warning -> 30
      MapLogLevel.Error -> 40
      UnspecifiedMapLogLevel -> 0
    }
