package org.maplibre.compose.logging

internal actual fun platformMapLogger(): MapLogger = MapLogger { record ->
  logToConsole(
    record.level.name.lowercase(),
    record.toPlatformLine(),
    record.throwable?.stackTraceToString(),
  )
}

private fun logToConsole(level: String, line: String, stack: String?): Unit =
  js(
    "{ const method = level === 'warning' ? 'warn' : level === 'debug' ? 'log' : level; if (stack == null) console[method](line); else console[method](line, stack); }"
  )
