@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.mlnffi

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking

/**
 * A count-down gate for tests, as `java.util.concurrent.CountDownLatch` is JVM-only.
 *
 * Waits run over [MlnFfiGate], so they carry its wakeup semantics: a wait on an open gate returns
 * at once.
 */
internal class TestLatch(count: Int) {
  private val gate = MlnFfiGate()
  private val remaining = AtomicLong(count.toLong())

  val count: Long
    get() = remaining.load()

  fun countDown() {
    if (remaining.addAndFetch(-1L) <= 0L) gate.open()
  }

  /** Waits without a bound. */
  fun await() {
    gate.await()
  }

  /** Waits up to [timeoutMillis], reporting whether the count reached zero. */
  fun await(timeoutMillis: Long): Boolean = gate.await(timeoutMillis)
}

/** Blocks the calling thread for [millis], as `Thread.sleep` does on the JVM. */
internal fun parkForTest(millis: Long) = runBlocking { delay(millis) }

/**
 * Starts [block] on a worker thread without waiting for it, as a raw `Thread` does. A failure
 * inside [block] surfaces through whatever synchronization the test awaits instead.
 *
 * The worker comes from the shared `Dispatchers.Default` pool, so [block] has no stable thread;
 * thread-affine work, such as a MapLibre runtime, belongs on a [TestThread].
 */
internal fun launchTestTask(block: () -> Unit) {
  CoroutineScope(Dispatchers.Default).launch { block() }
}

/**
 * One dedicated thread that runs every submitted task in submission order.
 *
 * MapLibre binds a runtime to its creating thread, so a test that keeps a runtime per thread spells
 * it with this rather than with a pool.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class TestThread(name: String) : AutoCloseable {
  private val threadContext = newSingleThreadContext(name)

  /** The thread as a dispatcher, for launching work that must not start on Default. */
  val dispatcher: CoroutineDispatcher = threadContext
  private val scope = CoroutineScope(dispatcher)

  /** Runs [block] on the thread and returns its result, throwing whatever it threw. */
  fun <T> submit(block: () -> T): T = runBlocking { scope.async { block() }.await() }

  override fun close() {
    threadContext.close()
  }
}
