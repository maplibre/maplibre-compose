package org.maplibre.compose.resource

/** Starts independent blocking work without allocating or shutting down a per-object executor. */
internal fun startMlnFfiBlockingWork(name: String, work: () -> Unit) {
  Thread.ofVirtual().name(name).start(work)
}
