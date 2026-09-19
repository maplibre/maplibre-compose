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
 * with [drain], or [loop] drains it when the test suspends: the `runTest` scheduler's dispatcher,
 * or the [runBlocking][kotlinx.coroutines.runBlocking] loop registered in [TestMain].
 */
internal class TestMainDispatcher(
  private val loop: CoroutineDispatcher? = null,
  private val thread: Any = currentThreadIdentity(),
) : CoroutineDispatcher() {
  private val lock = reentrantLock()
  private val queue = ArrayDeque<Runnable>()

  override fun isDispatchNeeded(context: CoroutineContext): Boolean =
    currentThreadIdentity() != thread

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    lock.withLock { queue.addLast(block) }
    // A loop that has already finished hands its queue to another thread; such a drain must wait
    // for the owner thread instead.
    (loop ?: TestMain.loop)?.dispatch(
      context,
      Runnable { if (currentThreadIdentity() == thread) drain() },
    )
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
