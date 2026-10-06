package org.maplibre.compose.logging

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Library-side log entry point with lazy messages. Reads [MapLogging.logger] at each call, so a
 * logger installed later receives the records. A null [MapLog] reference disables logging.
 *
 * Every record, including the engine's, goes through [log], which never lets an exception from the
 * installed logger reach its caller.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object MapLog {
  private val loggerFailureReported = AtomicBoolean(false)

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
    val minLevel =
      try {
        logger.minLevel
      } catch (error: Exception) {
        return reportLoggerFailure(logger, error)
      }
    if (level < minLevel) return
    val record = MapLogRecord(level, source, category, message(), throwable)
    try {
      logger.log(record)
    } catch (error: Exception) {
      reportLoggerFailure(logger, error)
    }
  }

  /**
   * Drops the record. The first failure in the process goes to the platform log, which doesn't
   * route through [MapLogging.logger], so the report can't recurse.
   */
  private fun reportLoggerFailure(logger: MapLogger, error: Exception) {
    val platformLogger = MapLogging.platformLogger
    if (logger === platformLogger || !loggerFailureReported.compareAndSet(false, true)) return
    try {
      platformLogger.log(
        MapLogRecord(
          MapLogLevel.Error,
          MapLogSource.Library,
          null,
          "MapLogging.logger threw an exception, so its record was dropped. " +
            "Later failures are not reported.",
          error,
        )
      )
    } catch (_: Exception) {}
  }
}
