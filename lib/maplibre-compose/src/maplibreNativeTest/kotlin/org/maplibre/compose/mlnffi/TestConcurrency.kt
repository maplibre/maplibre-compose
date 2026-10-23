@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.mlnffi

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A one-shot gate for tests, opened once and awaited by any number of threads. A wait on an open
 * gate returns at once.
 */
internal class TestGate {
  private val opened = CompletableDeferred<Unit>()

  fun open() {
    opened.complete(Unit)
  }

  /** Waits up to [timeoutMillis], reporting whether the gate opened. */
  fun await(timeoutMillis: Long): Boolean = runBlocking {
    withTimeoutOrNull(timeoutMillis) { opened.await() } != null
  }
}

/**
 * A count-down gate for tests, as `java.util.concurrent.CountDownLatch` is JVM-only.
 *
 * Waits run over [TestGate], so a wait on an open gate returns at once.
 */
internal class TestLatch(count: Int) {
  private val gate = TestGate()
  private val remaining = AtomicLong(count.toLong())

  val count: Long
    get() = remaining.load()

  fun countDown() {
    if (remaining.addAndFetch(-1L) <= 0L) gate.open()
  }

  /** Waits up to [timeoutMillis], reporting whether the count reached zero. */
  fun await(timeoutMillis: Long): Boolean = gate.await(timeoutMillis)
}

/** One dedicated thread that runs every submitted task in submission order. */
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
