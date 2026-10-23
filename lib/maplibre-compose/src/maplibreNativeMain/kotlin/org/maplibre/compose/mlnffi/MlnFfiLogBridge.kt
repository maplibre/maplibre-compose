package org.maplibre.compose.mlnffi

import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.logging.MapLogLevel
import org.maplibre.compose.logging.MapLogSource
import org.maplibre.compose.logging.MapLogging
import org.maplibre.nativeffi.generated.GeneratedApi
import org.maplibre.nativeffi.generated.LogEvent
import org.maplibre.nativeffi.generated.LogHandler
import org.maplibre.nativeffi.generated.LogSeverity
import org.maplibre.nativeffi.generated.logSetCallback

/**
 * Forwards MapLibre Native's process-global log stream to [MapLogging.logger] through [MapLog].
 *
 * Installed once, when the first native runtime is created; `logSetCallback` loads the native
 * library itself. The callback reads the current logger at each record, so replacing the logger
 * never reinstalls the callback. Every record is consumed, so a null logger or a filtered severity
 * does not fall through to the engine's platform logger.
 */
internal object MlnFfiLogBridge {
  private val lock = MlnFfiLock()
  private var installed = false

  fun ensureInstalled() {
    lock.withLock {
      if (installed) return
      GeneratedApi.logSetCallback(LogHandler(::forward))
      installed = true
    }
  }

  private fun forward(severity: LogSeverity, event: LogEvent, code: Long, message: String): UInt {
    MapLog.log(
      level = severity.toMapLogLevel(),
      throwable = null,
      message = { if (code >= 0) "$message (code $code)" else message },
      source = MapLogSource.NativeEngine,
      category = event.categoryName(),
    )
    // Consumed, so the engine's platform logger stays quiet.
    return 1u
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
    LogEvent.GRAPHICS_BACKEND -> "GraphicsBackend"
    LogEvent.JNI -> "Jni"
    LogEvent.ANDROID -> "Android"
    LogEvent.CRASH -> "Crash"
    LogEvent.GLYPH -> "Glyph"
    LogEvent.TIMING -> "Timing"
    else -> "Event$rawValue"
  }
