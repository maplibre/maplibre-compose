package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable

/**
 * A main dispatcher for tests that drive a map from one thread: inline on [thread], like
 * `Dispatchers.Main.immediate`, and a queue from any other thread. A test drains the queue itself
 * with [drain], or the [runBlocking][kotlinx.coroutines.runBlocking] loop registered in [TestMain]
 * drains it when the test suspends.
 */
internal class TestMainDispatcher(private val thread: Any = currentThreadIdentity()) :
  CoroutineDispatcher() {
  private val lock = reentrantLock()
  private val queue = ArrayDeque<Runnable>()

  override fun isDispatchNeeded(context: CoroutineContext): Boolean =
    currentThreadIdentity() != thread

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    lock.withLock { queue.addLast(block) }
    TestMain.loop?.dispatch(context, Runnable { drain() })
  }

  /** Runs every queued block on the calling thread, which must be [thread]. */
  fun drain() {
    check(currentThreadIdentity() == thread) { "Drain the test main from its own thread" }
    while (true) {
      val block = lock.withLock { queue.removeFirstOrNull() } ?: return
      block.run()
    }
  }
}

/** The event loop of the `runBlocking` a map test runs in, while one is running. */
internal object TestMain {
  @Volatile var loop: CoroutineDispatcher? = null
}
