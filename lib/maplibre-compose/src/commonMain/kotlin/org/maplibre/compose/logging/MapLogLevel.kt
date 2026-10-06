package org.maplibre.compose.logging

/**
 * Severity of a [MapLogRecord], in ascending order.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public enum class MapLogLevel {
  Debug,
  Info,
  Warning,
  Error,
}
