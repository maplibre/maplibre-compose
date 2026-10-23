package org.maplibre.compose.map

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.maplibre.compose.mlnffi.parkUntilDone

/**
 * Forgets and closes the process default, and parks the calling thread until it has closed. Call it
 * after a test body, where the thread has nothing left to run.
 */
internal fun DefaultMapRuntime.resetForTest(testFailure: Throwable? = null) {
  val runtime = clearForTest() ?: return
  try {
    parkUntilDone(30.seconds) { runtime.awaitClosed() }
  } catch (error: Throwable) {
    if (testFailure == null) throw error
    if (testFailure !== error) testFailure.addSuppressed(error)
  }
}

internal suspend fun <T> withTestRuntime(
  from: MapRuntimeOptions = MapRuntimeOptions.Standard,
  configure: MapRuntimeOptions.Builder.() -> Unit = {},
  block: suspend (MapRuntime) -> T,
): T {
  val runtime = createMapRuntime(from, configure)
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
