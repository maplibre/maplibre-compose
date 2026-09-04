package org.maplibre.compose.map

import kotlinx.coroutines.runBlocking

/** Forgets and closes the process default, and waits until it has closed. */
internal fun DefaultMapRuntime.resetForTest(): Boolean {
  val runtime = clearForTest() ?: return true
  return runCatching { runBlocking { runtime.awaitClosed() } }.isSuccess
}
