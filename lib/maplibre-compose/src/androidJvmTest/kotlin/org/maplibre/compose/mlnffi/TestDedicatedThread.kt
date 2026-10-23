package org.maplibre.compose.mlnffi

internal actual class TestDedicatedThread actual constructor(name: String, body: () -> Unit) {
  private val delegate = Thread(body, name).apply { isDaemon = true }

  actual fun start() {
    delegate.start()
  }

  actual fun isCurrent(): Boolean = Thread.currentThread() === delegate
}
