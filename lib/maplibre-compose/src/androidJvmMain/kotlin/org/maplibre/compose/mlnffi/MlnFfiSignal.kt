package org.maplibre.compose.mlnffi

import java.util.concurrent.Semaphore

internal actual class MlnFfiSignal actual constructor() {
  private val permits = Semaphore(0)

  actual fun signal() {
    permits.release()
  }

  actual fun await() {
    permits.acquireUninterruptibly()
    permits.drainPermits()
  }
}
