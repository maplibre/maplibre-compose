package org.maplibre.compose.mlnffi

/** One real thread that runs [body] once, for test hosts that drive a renderer thread. */
internal expect class TestDedicatedThread(name: String, body: () -> Unit) {
  fun start()

  /** Whether the calling thread is this one. */
  fun isCurrent(): Boolean
}
