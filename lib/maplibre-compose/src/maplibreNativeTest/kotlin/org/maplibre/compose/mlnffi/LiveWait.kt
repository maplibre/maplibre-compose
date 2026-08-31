package org.maplibre.compose.mlnffi

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MlnFfiMapSession

/**
 * Presentation, style load, attach count, and native layer ids for a live Compose wait timeout.
 *
 * A missing [state] still reports null presentation so the failure names the seam.
 */
internal fun liveWaitDiagnostics(state: MapState?, extra: String = ""): String {
  val presentation = state?.presentation
  val session = presentation?.adapter as? MlnFfiMapSession
  return buildList {
      add("presentation=${if (presentation == null) "null" else "attached"}")
      if (presentation != null) add("valid=${presentation.isValid}")
      add("style=${state?.style?.loadState}")
      add("closed=${state?.isClosed}")
      if (session != null) {
        add("attachCount=${session.attachCount}")
        add(
          "layers=${runCatching { session.currentStyleLayerIds() }.getOrElse { "error:${it.message}" }}"
        )
      }
      if (extra.isNotEmpty()) add(extra)
    }
    .joinToString(", ")
}

/**
 * Pumps Compose frames until [condition] holds. Timeout is an [AssertionError] with
 * [liveWaitDiagnostics], not a silent runner cancel.
 *
 * A blocked native render still never reaches this check. The hang watchdog is the backstop for
 * that path. Each call pings the watchdog so a long test with many waits is not killed at 50 s.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.waitUntilLive(
  what: String,
  timeoutMillis: Long = 30_000,
  state: MapState? = null,
  extra: () -> String = { "" },
  condition: () -> Boolean,
) {
  pingFfiTestHangWatchdog(timeoutMillis + HANG_WATCHDOG_SLACK_MILLIS)
  try {
    waitUntil(conditionDescription = what, timeoutMillis = timeoutMillis, condition = condition)
  } catch (error: Throwable) {
    if (!isComposeWaitTimeout(error)) throw error
    throw AssertionError(
      "Timed out waiting for $what. ${liveWaitDiagnostics(state, extra())}",
      error,
    )
  }
}

/** Extra time the hang watchdog allows after a single [waitUntilLive] timeout. */
internal const val HANG_WATCHDOG_SLACK_MILLIS = 20_000L

private fun isComposeWaitTimeout(error: Throwable): Boolean {
  if (error is AssertionError) return true
  return error::class.simpleName == "ComposeTimeoutException"
}
