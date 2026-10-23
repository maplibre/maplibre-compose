@file:OptIn(ExperimentalAtomicApi::class, ExperimentalThreadBlockingApi::class)

package org.maplibre.compose.mlnffi

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.ExperimentalThreadBlockingApi
import kotlinx.atomicfu.locks.ParkingHandle
import kotlinx.atomicfu.locks.ParkingSupport
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import org.maplibre.compose.map.currentThreadIdentity

/**
 * A one-shot gate for tests, opened once and awaited by any number of threads. A wait parks its
 * thread, so it stands in for code that blocks, such as an application's blocking read or a host
 * that waits for its renderer. A wait on an open gate returns at once.
 */
internal class TestGate {
  private val opened = AtomicBoolean(false)
  private val lock = reentrantLock()
  private val waiters = mutableListOf<ParkingHandle>()

  fun open() {
    opened.store(true)
    lock.withLock { waiters.toList() }.forEach { ParkingSupport.unpark(it) }
  }

  /** Parks the calling thread for up to [timeout], reporting whether the gate opened. */
  fun await(timeout: Duration): Boolean {
    if (opened.load()) return true
    val deadline = TimeSource.Monotonic.markNow() + timeout
    val waiter = ParkingSupport.currentThreadHandle()
    lock.withLock { waiters += waiter }
    try {
      // Parking can wake spuriously, so each wake checks the gate again.
      while (!opened.load()) {
        if (deadline.hasPassedNow()) return false
        ParkingSupport.parkUntil(deadline)
      }
      return true
    } finally {
      lock.withLock { waiters -= waiter }
    }
  }

  /** Parks the calling thread for up to [timeoutMillis], reporting whether the gate opened. */
  fun await(timeoutMillis: Long): Boolean = await(timeoutMillis.milliseconds)
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

/**
 * Parks the calling thread until [block] finishes, and returns its result. [block] starts on the
 * calling thread and resumes wherever its awaited work completes, so it must not need the calling
 * thread to finish.
 */
internal fun <T> parkUntilDone(timeout: Duration, block: suspend () -> T): T {
  val done = TestGate()
  var result: Result<T>? = null
  CoroutineScope(Dispatchers.Unconfined).launch {
    result = runCatching { block() }
    done.open()
  }
  check(done.await(timeout)) { "Timed out after $timeout" }
  return checkNotNull(result).getOrThrow()
}

/** One dedicated thread that runs every submitted task in submission order. */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class TestThread(name: String) : AutoCloseable {
  private val threadContext = newSingleThreadContext(name)

  /** The thread as a dispatcher, for launching work that must not start on Default. */
  val dispatcher: CoroutineDispatcher = threadContext
  private val scope = CoroutineScope(dispatcher)

  // Set by the thread's first task; until then, no caller is on the thread.
  @Volatile private var identity: Any? = null

  init {
    scope.launch { identity = currentThreadIdentity() }
  }

  /**
   * Runs [block] on the thread and parks the caller until it returns, as a host waits for its
   * renderer thread. A call from the thread itself runs [block] directly. Returns its result,
   * throwing whatever it threw.
   */
  fun <T> submit(block: () -> T): T {
    if (identity != null && currentThreadIdentity() == identity) return block()
    val done = TestGate()
    var result: Result<T>? = null
    scope.launch {
      result = runCatching(block)
      done.open()
    }
    check(done.await(SubmitTimeout)) { "The test thread did not run a task within $SubmitTimeout" }
    return checkNotNull(result).getOrThrow()
  }

  override fun close() {
    threadContext.close()
  }

  private companion object {
    val SubmitTimeout = 30.seconds
  }
}
