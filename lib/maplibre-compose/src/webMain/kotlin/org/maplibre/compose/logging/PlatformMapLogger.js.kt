package org.maplibre.compose.logging

internal actual fun platformMapLogger(): MapLogger = MapLogger { record ->
  val line = record.toPlatformLine()
  val level =
    when {
      record.level >= MapLogLevel.Error -> "error"
      record.level >= MapLogLevel.Warning -> "warning"
      record.level >= MapLogLevel.Info -> "info"
      else -> "debug"
    }
  logToConsole(level, line, record.throwable?.stackTraceToString())
}

private fun logToConsole(level: String, line: String, stack: String?): Unit =
  js(
    "{ const method = level === 'warning' ? 'warn' : level === 'debug' ? 'log' : level; if (stack == null) console[method](line); else console[method](line, stack); }",
  )
