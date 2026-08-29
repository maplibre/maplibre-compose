package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class HostDispatcherTest {

  @Test
  fun resolveHostDispatcher_uses_main_when_it_is_installed() {
    val main = Dispatchers.Unconfined
    val fallback = Dispatchers.Default
    assertSame(main, resolveHostDispatcher(main = { main }, fallback = { fallback }))
  }

  @Test
  fun resolveHostDispatcher_uses_fallback_when_main_is_missing() {
    val fallback = Dispatchers.Unconfined
    val resolved =
      resolveHostDispatcher(main = { error("Main is missing") }, fallback = { fallback })
    assertSame(fallback, resolved)
  }

  @Test
  fun defaultHostDispatcher_runs_a_task() = runBlocking {
    withContext(defaultHostDispatcher()) { /* the dispatcher accepted the hop */ }
  }
}
