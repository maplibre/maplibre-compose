package org.maplibre.compose.logging

/**
 * Receives every diagnostic from the library and the map engines.
 *
 * [log] runs on any thread, including engine worker threads, and may run while the native engine
 * holds its logging lock. An implementation returns quickly and calls no map API.
 */
public fun interface MapLogger {
  /** The library drops records below this level before it builds their message. */
  public val minLevel: MapLogLevel
    get() = MapLogLevel.Debug

  public fun log(record: MapLogRecord)
}
