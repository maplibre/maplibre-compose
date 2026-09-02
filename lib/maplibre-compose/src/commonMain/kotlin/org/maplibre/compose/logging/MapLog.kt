package org.maplibre.compose.logging

/**
 * Library-side log entry point with lazy messages. Reads [MapLogging.logger] at each call, so a
 * logger installed later receives the records. A null [MapLog] reference disables logging.
 */
internal object MapLog {
  fun d(throwable: Throwable? = null, message: () -> String) =
    log(MapLogLevel.Debug, throwable, message)

  fun i(throwable: Throwable? = null, message: () -> String) =
    log(MapLogLevel.Info, throwable, message)

  fun w(throwable: Throwable? = null, message: () -> String) =
    log(MapLogLevel.Warning, throwable, message)

  fun e(throwable: Throwable? = null, message: () -> String) =
    log(MapLogLevel.Error, throwable, message)

  fun log(
    level: MapLogLevel,
    throwable: Throwable?,
    message: () -> String,
    source: MapLogSource = MapLogSource.Library,
    category: String? = null,
  ) {
    val logger = MapLogging.logger ?: return
    if (level < logger.minLevel) return
    logger.log(MapLogRecord(level, source, category, message(), throwable))
  }
}
