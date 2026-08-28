package org.maplibre.compose.testing

import kotlin.concurrent.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.posix.usleep

/**
 * The Kotlin/Native test runner calls this on the main thread, and the map delivers snapshot apply
 * notifications through Dispatchers.Main, so the test body runs on a worker while the main thread
 * keeps its run loop serving the main queue.
 */
internal actual fun runMapTest(block: suspend () -> Unit): MapTestResult {
  val outcome = AtomicReference<Result<Unit>?>(null)
  CoroutineScope(Dispatchers.Default).launch { outcome.value = runCatching { block() } }
  while (outcome.value == null) {
    val ranSource =
      NSRunLoop.mainRunLoop.runMode(NSDefaultRunLoopMode, NSDate.dateWithTimeIntervalSinceNow(0.1))
    if (!ranSource) usleep(10_000u)
  }
  outcome.value!!.getOrThrow()
}
