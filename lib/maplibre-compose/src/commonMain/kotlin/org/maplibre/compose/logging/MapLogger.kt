package org.maplibre.compose.logging

/**
 * Receives every diagnostic from the library and the map engines.
 *
 * On MapLibre Native platforms, [log] runs on any thread: the map's main thread, library background
 * threads, and engine worker and logging threads. Calls from different threads can run at the same
 * time, and a call can run while the native engine holds its logging lock. On the browser, [log]
 * runs on the page's main thread. An implementation returns quickly, is safe to call concurrently,
 * and calls no map API.
 */
public fun interface MapLogger {
  /**
   * The lowest level this logger receives. The library drops records below this level before it
   * builds their message.
   *
   * The library reads this value for each record, on the thread that writes the record.
   */
  public val minLevel: MapLogLevel
    get() = MapLogLevel.Debug

  /**
   * Writes one record.
   *
   * Must not throw. If it throws for a record from [MapLogSource.NativeEngine], the exception is
   * discarded and MapLibre Native writes the record to its own platform log. If it throws for any
   * other record, the exception propagates into the library code that wrote the record, and the
   * operation that was running can fail.
   */
  public fun log(record: MapLogRecord)
}
