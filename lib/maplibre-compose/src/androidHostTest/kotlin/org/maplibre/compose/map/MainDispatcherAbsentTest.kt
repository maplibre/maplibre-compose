package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Android host tests have no main looper, so no main dispatcher is installed. */
class MainDispatcherAbsentTest {
  @Test
  fun a_runtime_without_a_main_dispatcher_fails_at_creation() {
    val error =
      assertFailsWith<IllegalStateException> {
        RuntimeImplementation(platformContext = null, closeResources = {}, logger = null)
      }
    assertTrue(error.message.orEmpty().contains("main dispatcher"))
  }
}
