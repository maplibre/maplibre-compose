package org.maplibre.compose.map

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import java.util.Collections

internal class CapturingLogWriter : LogWriter() {
  data class LogEntry(val severity: Severity, val message: String)

  private val _logs = Collections.synchronizedList(mutableListOf<LogEntry>())

  override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
    _logs.add(LogEntry(severity, message))
  }

  fun errors(): List<LogEntry> =
    synchronized(_logs) { _logs.filter { it.severity >= Severity.Error } }
}
