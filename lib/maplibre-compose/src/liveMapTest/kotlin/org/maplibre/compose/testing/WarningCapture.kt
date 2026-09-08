package org.maplibre.compose.testing

import org.maplibre.compose.logging.MapLogLevel
import org.maplibre.compose.logging.MapLogger
import org.maplibre.compose.logging.MapLogging

/**
 * Runs [block] with the library's warnings and errors collected in the returned list, in addition
 * to reaching the logger that was installed before. A posted style write reports an engine
 * rejection through the logger, so a test reads the report from this list.
 */
internal suspend fun <T> captureWarnings(block: suspend (warnings: List<String>) -> T): T {
  val previous = MapLogging.logger
  val captured = mutableListOf<String>()
  MapLogging.logger = MapLogger { record ->
    if (record.level >= MapLogLevel.Warning) captured += record.message
    previous?.log(record)
  }
  try {
    return block(captured)
  } finally {
    MapLogging.logger = previous
  }
}
