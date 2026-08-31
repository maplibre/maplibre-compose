package org.maplibre.compose.mlnffi

import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LiveWaitTest {

  @Test
  fun liveWaitDiagnostics_reports_a_null_state() {
    val dump = liveWaitDiagnostics(state = null, extra = "events=[]")
    assertContains(dump, "presentation=null")
    assertContains(dump, "style=null")
    assertContains(dump, "closed=null")
    assertContains(dump, "events=[]")
    assertFalse(dump.contains("attachCount="))
    assertFalse(dump.contains("layers="))
  }

  @Test
  fun waitUntilLive_reports_diagnostics_when_the_condition_never_holds() = runPlainComposeUiTest {
    val error =
      assertFailsWith<AssertionError> {
        waitUntilLive(
          "a condition that never holds",
          timeoutMillis = 50,
          extra = { "events=[]" },
        ) {
          false
        }
      }
    val message = error.message.orEmpty()
    assertContains(message, "a condition that never holds")
    assertContains(message, "presentation=null")
    assertContains(message, "events=[]")
    assertTrue(error.cause != null, "the Compose timeout should be the cause")
  }
}
