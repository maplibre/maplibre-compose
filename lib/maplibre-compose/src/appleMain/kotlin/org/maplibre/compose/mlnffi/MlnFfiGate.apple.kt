package org.maplibre.compose.mlnffi

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.autoreleasepool
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * A gate over a condition variable. Every wait loops over the open flag, because a condition
 * variable returns from a spurious wakeup as readily as from a signal.
 *
 * Kotlin/Native threads cannot be interrupted, so [await] and [awaitUntilOpen] are the same wait.
 */
internal actual class MlnFfiGate actual constructor() {
  private val condition = NSCondition()
  private var isOpen = false

  actual fun open() {
    condition.lock()
    try {
      isOpen = true
      condition.broadcast()
    } finally {
      condition.unlock()
    }
  }

  actual fun await() {
    condition.lock()
    try {
      while (!isOpen) condition.wait()
    } finally {
      condition.unlock()
    }
  }

  actual fun awaitUntilOpen() {
    await()
  }

  @OptIn(BetaInteropApi::class)
  actual fun await(timeoutMillis: Long): Boolean {
    condition.lock()
    try {
      // A wait can land on a pool-less thread, where an autoreleased NSDate leaks, so the timed
      // wait runs inside a pool of its own.
      return autoreleasepool {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutMillis / 1000.0)
        var timedOut = false
        while (!isOpen && !timedOut) {
          timedOut = !condition.waitUntilDate(deadline)
        }
        isOpen
      }
    } finally {
      condition.unlock()
    }
  }
}
