package org.maplibre.compose.mlnffi

import java.util.concurrent.locks.ReentrantLock

internal actual class MlnFfiLock actual constructor() {
  private val delegate = ReentrantLock()

  actual fun lock() {
    delegate.lock()
  }

  actual fun unlock() {
    delegate.unlock()
  }
}
