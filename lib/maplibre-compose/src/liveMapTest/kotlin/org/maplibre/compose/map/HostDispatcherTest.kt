package org.maplibre.compose.map

import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable

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
  fun resolveHostDispatcher_uses_fallback_when_main_throws_on_probe() {
    val fallback = Dispatchers.Unconfined
    val sentinel =
      object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean {
          error("Module with the Main dispatcher is missing")
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
          error("Module with the Main dispatcher is missing")
        }
      }
    val resolved = resolveHostDispatcher(main = { sentinel }, fallback = { fallback })
    assertSame(fallback, resolved)
  }
}
