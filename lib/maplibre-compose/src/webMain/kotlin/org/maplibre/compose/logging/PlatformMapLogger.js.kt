package org.maplibre.compose.logging

internal actual fun platformMapLogger(): MapLogger = MapLogger { record ->
  val line = record.toPlatformLine()
  val args = if (record.throwable == null) arrayOf<Any?>(line) else arrayOf(line, record.throwable)
  when {
    record.level >= MapLogLevel.Error -> console.error(*args)
    record.level >= MapLogLevel.Warning -> console.warn(*args)
    record.level >= MapLogLevel.Info -> console.info(*args)
    else -> console.log(*args)
  }
}

private fun logToConsole(level: String, line: String, stack: String?): Unit =
  js(
    "{ const method = level === 'warning' ? 'warn' : level === 'debug' ? 'log' : level; if (stack == null) console[method](line); else console[method](line, stack); }"
  )
