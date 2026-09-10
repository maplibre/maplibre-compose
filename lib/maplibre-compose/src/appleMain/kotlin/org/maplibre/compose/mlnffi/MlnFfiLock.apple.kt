package org.maplibre.compose.mlnffi

import platform.Foundation.NSRecursiveLock

/**
 * Darwin offers no FIFO-fair lock primitive, so [fair] is accepted and ignored; `NSRecursiveLock`
 * grants the lock to whichever waiter the scheduler wakes first.
 *
 * The contract forbids reentry, and this lock stays recursive anyway: a violation then surfaces as
 * the logic error it is, where a plain `NSLock` would deadlock the thread and hide it.
 */
internal actual class MlnFfiLock actual constructor(fair: Boolean) {
  private val delegate = NSRecursiveLock()

  actual fun lock() {
    delegate.lock()
  }

  actual fun unlock() {
    delegate.unlock()
  }
}
