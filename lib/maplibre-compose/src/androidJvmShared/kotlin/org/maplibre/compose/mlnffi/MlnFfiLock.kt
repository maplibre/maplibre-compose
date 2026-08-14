package org.maplibre.compose.mlnffi

import java.util.concurrent.locks.ReentrantLock

internal actual class MlnFfiLock actual constructor(fair: Boolean) {
  private val delegate = ReentrantLock(fair)

  actual fun lock() {
    delegate.lock()
  }

  actual fun unlock() {
    delegate.unlock()
  }
}
