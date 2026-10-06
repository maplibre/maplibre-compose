package org.maplibre.compose.logging

/**
 * The component that produced a [MapLogRecord].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface MapLogSource {
  /** MapLibre Compose itself. */
  public data object Library : MapLogSource

  /** MapLibre Native, on Android, iOS, and desktop. */
  public data object NativeEngine : MapLogSource

  /** MapLibre GL JS, in the browser. */
  public data object WebEngine : MapLogSource
}

/**
 * Keeps [MapLogSource] open: callers' `when` needs an `else` branch. The library never reports it.
 */
internal data object UnspecifiedMapLogSource : MapLogSource
