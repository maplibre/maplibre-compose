package org.maplibre.compose.map

import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class HostDispatcherJvmTest {

  @Test
  fun defaultHostDispatcher_runs_a_task() = runBlocking {
    withContext(defaultHostDispatcher()) { /* the dispatcher accepted the hop */ }
  }
}
