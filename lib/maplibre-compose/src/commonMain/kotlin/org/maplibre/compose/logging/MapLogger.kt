package org.maplibre.compose.logging

/**
 * Receives every diagnostic from the library and the map engines.
 *
 * On MapLibre Native platforms, [log] can run on any thread, including the engine's own threads,
 * and calls from different threads can run at the same time. On the browser, [log] runs on the
 * page's main thread. An implementation returns quickly, is safe to call concurrently, and calls no
 * map API.
 */
public fun interface MapLogger {
  /**
   * The lowest level this logger receives. The library drops records below this level before it
   * builds their message.
   *
   * The library reads this value for each record, on the thread that writes the record. If reading
   * it throws an exception, the library drops that record.
   */
  public val minLevel: MapLogLevel
    get() = MapLogLevel.Debug

  /**
   * Writes one record.
   *
   * Implementations should not throw. If one throws an exception, the library drops that record.
   * The exception never reaches the map operation that wrote the record.
   */
  public fun log(record: MapLogRecord)
}
