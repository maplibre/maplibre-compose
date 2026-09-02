package org.maplibre.compose.mlnffi

import org.maplibre.compose.logging.MapLogLevel
import org.maplibre.compose.logging.MapLogRecord
import org.maplibre.compose.logging.MapLogSource
import org.maplibre.compose.logging.MapLogging
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.log.LogCallback
import org.maplibre.nativeffi.log.LogEvent
import org.maplibre.nativeffi.log.LogRecord
import org.maplibre.nativeffi.log.LogSeverity

/**
 * Forwards MapLibre Native's process-global log stream to [MapLogging.logger].
 *
 * Installed once, when the first native runtime is created; `setLogCallback` loads the native
 * library itself. The callback reads the current logger at each record, so replacing the logger
 * never reinstalls the callback. Every record is consumed: the engine's own fall-through sink is
 * stderr on every platform this build targets (maplibre/maplibre-native-ffi#679).
 */
internal object MlnFfiLogBridge {
  private val lock = MlnFfiLock()
  private var installed = false

  fun ensureInstalled() {
    lock.withLock {
      if (installed) return
      Maplibre.setLogCallback(LogCallback(::forward))
      installed = true
    }
  }

  private fun forward(record: LogRecord): Boolean {
    val logger = MapLogging.logger ?: return true
    val level = record.severity.toMapLogLevel()
    if (level < logger.minLevel) return true
    val message =
      if (record.code >= 0) "${record.message} (code ${record.code})" else record.message
    logger.log(
      MapLogRecord(level, MapLogSource.NativeEngine, record.event.categoryName(), message, null)
    )
    return true
  }
}

private fun LogSeverity.toMapLogLevel(): MapLogLevel =
  when (this) {
    LogSeverity.INFO -> MapLogLevel.Info
    LogSeverity.WARNING -> MapLogLevel.Warning
    LogSeverity.ERROR -> MapLogLevel.Error
    else -> MapLogLevel.Warning
  }

private fun LogEvent.categoryName(): String =
  when (this) {
    LogEvent.GENERAL -> "General"
    LogEvent.SETUP -> "Setup"
    LogEvent.SHADER -> "Shader"
    LogEvent.PARSE_STYLE -> "ParseStyle"
    LogEvent.PARSE_TILE -> "ParseTile"
    LogEvent.RENDER -> "Render"
    LogEvent.STYLE -> "Style"
    LogEvent.DATABASE -> "Database"
    LogEvent.HTTP_REQUEST -> "HttpRequest"
    LogEvent.SPRITE -> "Sprite"
    LogEvent.IMAGE -> "Image"
    LogEvent.OPENGL -> "OpenGL"
    LogEvent.JNI -> "Jni"
    LogEvent.ANDROID -> "Android"
    LogEvent.CRASH -> "Crash"
    LogEvent.GLYPH -> "Glyph"
    LogEvent.TIMING -> "Timing"
    else -> "Event$nativeValue"
  }
