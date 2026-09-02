package org.maplibre.compose.logging

import kotlin.concurrent.Volatile

/** Process-wide logging configuration for every map runtime. */
public object MapLogging {
  /**
   * Writes each record to the platform log: logcat, NSLog, standard error, or the browser console.
   */
  public val platformLogger: MapLogger = platformMapLogger()

  /** The sink for every record. Null drops every record. Defaults to [platformLogger]. */
  @Volatile public var logger: MapLogger? = platformLogger
}

internal expect fun platformMapLogger(): MapLogger

internal const val MAP_LOG_TAG: String = "maplibre-compose"

/** The message, prefixed with the category when the engine reported one. */
internal fun MapLogRecord.categorizedMessage(): String =
  if (category == null) message else "[$category] $message"

/** The platform log line: the tag and the categorized message. */
internal fun MapLogRecord.toPlatformLine(): String = "$MAP_LOG_TAG: ${categorizedMessage()}"
