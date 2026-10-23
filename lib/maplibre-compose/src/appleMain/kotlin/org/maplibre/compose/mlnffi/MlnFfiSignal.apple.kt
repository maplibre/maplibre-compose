package org.maplibre.compose.mlnffi

import platform.Foundation.NSCondition

/** Loops over its flag, because a condition variable can wake without a signal. */
internal actual class MlnFfiSignal actual constructor() {
  private val condition = NSCondition()
  private var signalled = false

  actual fun signal() {
    condition.lock()
    try {
      signalled = true
      condition.signal()
    } finally {
      condition.unlock()
    }
  }

  actual fun await() {
    condition.lock()
    try {
      while (!signalled) condition.wait()
      signalled = false
    } finally {
      condition.unlock()
    }
  }
}
