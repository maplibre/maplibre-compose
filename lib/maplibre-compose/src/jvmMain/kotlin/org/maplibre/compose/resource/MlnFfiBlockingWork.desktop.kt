package org.maplibre.compose.resource

internal actual fun startMlnFfiBlockingWork(name: String, work: () -> Unit) {
  Thread.ofVirtual().name(name).start(work)
}
