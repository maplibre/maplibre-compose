package org.maplibre.compose.map

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import java.util.Collections

internal class CapturingLogWriter : LogWriter() {
  data class LogEntry(
    val severity: Severity,
    val message: String,
    val tag: String,
    val throwable: Throwable?,
  )

  private val _logs = Collections.synchronizedList(mutableListOf<LogEntry>())
  val logs: List<LogEntry>
    get() = _logs.toList()

  override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
    _logs.add(LogEntry(severity, message, tag, throwable))
  }

  fun errors(): List<LogEntry> = logs.filter { it.severity >= Severity.Error }
}
