package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

/** Checks the public main-dispatcher configuration. */
class MainConfinementTest {

  @Test
  fun a_runtime_rejects_an_unconfined_main_dispatcher() {
    val error =
      assertFailsWith<IllegalArgumentException> {
        mapRuntimeForTest(mainDispatcher = Dispatchers.Unconfined)
      }
    assertTrue(error.message.orEmpty().contains("Dispatchers.Unconfined"))
  }
}
