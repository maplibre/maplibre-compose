package org.maplibre.compose.testing

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig

/** One entry that a [RecordingLogger] kept. */
internal data class LogRecord(
  val severity: Severity,
  val message: String,
  val throwable: Throwable?,
)

/**
 * A [Logger] that keeps every entry at or above [minSeverity], so a test can assert over what the
 * library logged.
 */
internal class RecordingLogger(tag: String, private val minSeverity: Severity = Severity.Error) {

  val records: MutableList<LogRecord> = mutableListOf()

  /** The messages kept so far, which is all that most assertions need. */
  val messages: List<String>
    get() = records.map { it.message }

  /** The throwables kept so far, for a failure that the library logged rather than threw. */
  val throwables: List<Throwable>
    get() = records.mapNotNull { it.throwable }

  val logger: Logger =
    Logger(
      config =
        StaticConfig(
          logWriterList =
            listOf(
              object : LogWriter() {
                override fun log(
                  severity: Severity,
                  message: String,
                  tag: String,
                  throwable: Throwable?,
                ) {
                  if (severity >= minSeverity) records += LogRecord(severity, message, throwable)
                }
              }
            )
        ),
      tag = tag,
    )
}
