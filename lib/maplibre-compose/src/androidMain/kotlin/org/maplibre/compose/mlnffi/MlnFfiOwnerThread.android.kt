package org.maplibre.compose.mlnffi

internal actual class MlnFfiOwnerThread actual constructor(name: String, body: () -> Unit) {
  private val delegate = Thread(body, name)

  actual fun start() {
    delegate.start()
  }

  actual fun isCurrent(): Boolean = Thread.currentThread() === delegate

  actual fun join(timeoutMillis: Long): Boolean =
    try {
      delegate.join(timeoutMillis)
      !delegate.isAlive
    } catch (interruption: InterruptedException) {
      Thread.currentThread().interrupt()
      !delegate.isAlive
    }
}

internal actual fun currentMlnFfiThreadName(): String = Thread.currentThread().name
