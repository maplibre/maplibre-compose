package org.maplibre.compose.map

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Forgets and closes the process default, and waits until it has closed. */
internal fun DefaultMapRuntime.resetForTest(testFailure: Throwable? = null) {
  val runtime = clearForTest() ?: return
  try {
    runBlocking { runtime.awaitClosed() }
  } catch (error: Throwable) {
    if (testFailure == null) throw error
    if (testFailure !== error) testFailure.addSuppressed(error)
  }
}

internal suspend fun <T> withTestRuntime(
  options: MapRuntimeOptions,
  block: suspend (MapRuntime) -> T,
): T {
  val runtime = createMapRuntime(options)
  var failure: Throwable? = null
  try {
    return block(runtime)
  } catch (error: Throwable) {
    failure = error
    throw error
  } finally {
    try {
      runtime.close()
      withContext(NonCancellable) { runtime.awaitClosed() }
    } catch (error: Throwable) {
      val primary = failure
      if (primary == null) throw error else if (primary !== error) primary.addSuppressed(error)
    }
  }
}
