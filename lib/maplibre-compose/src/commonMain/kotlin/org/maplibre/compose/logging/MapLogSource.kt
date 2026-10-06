package org.maplibre.compose.logging

/**
 * The component that produced a [MapLogRecord].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public enum class MapLogSource {
  /** MapLibre Compose itself. */
  Library,
  /** MapLibre Native, on Android, iOS, and desktop. */
  NativeEngine,
  /** MapLibre GL JS, in the browser. */
  WebEngine,
}
