package org.maplibre.compose.logging

/**
 * Receives every diagnostic from the library and the map engines.
 *
 * [log] runs on any thread, including engine worker threads, and may run while the native engine
 * holds its logging lock. Return quickly and call no map API from it.
 */
public fun interface MapLogger {
  /** Records below this level are dropped before their message is built. */
  public val minLevel: MapLogLevel
    get() = MapLogLevel.Debug

  public fun log(record: MapLogRecord)
}
