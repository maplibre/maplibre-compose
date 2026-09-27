package org.maplibre.compose.mlnffi

internal actual class MlnFfiOwnerThread actual constructor(name: String, body: () -> Unit) {
  private val delegate =
    Thread(body, name).apply {
      // A parked native pump ignores interruption, so a host that exits while a loop is still
      // running has no way to stop it and must be free to leave it behind.
      isDaemon = true
    }

  actual fun start() {
    delegate.start()
  }

  actual fun isCurrent(): Boolean = Thread.currentThread() === delegate
}

internal actual fun currentMlnFfiThreadName(): String = Thread.currentThread().name
