package org.maplibre.compose.map

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private val processHostDispatcher: CoroutineDispatcher by lazy { ProcessHostDispatcher() }

internal actual fun defaultHostDispatcher(): CoroutineDispatcher =
  resolveHostDispatcher(main = { Dispatchers.Main.immediate }, fallback = { processHostDispatcher })

internal actual fun <T> runBlockingOn(dispatcher: CoroutineDispatcher, block: () -> T): T =
  runBlocking {
    withContext(dispatcher) { block() }
  }

/**
 * A process-lifetime host thread. [isDispatchNeeded] is false on that thread so a commit from a
 * posted callback stays inline and does not deadlock the single worker.
 */
private class ProcessHostDispatcher : CoroutineDispatcher() {
  private val thread = AtomicReference<Thread?>(null)
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "maplibre-compose-host").apply {
      isDaemon = true
      thread.set(this)
    }
  }

  override fun isDispatchNeeded(context: CoroutineContext): Boolean =
    Thread.currentThread() !== thread.get()

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    executor.execute(block)
  }
}
