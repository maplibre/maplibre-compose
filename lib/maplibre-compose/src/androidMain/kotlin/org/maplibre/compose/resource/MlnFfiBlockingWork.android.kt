package org.maplibre.compose.resource

internal actual fun startMlnFfiBlockingWork(name: String, work: () -> Unit) {
  Thread(work, name).apply {
    isDaemon = true
    start()
  }
}
