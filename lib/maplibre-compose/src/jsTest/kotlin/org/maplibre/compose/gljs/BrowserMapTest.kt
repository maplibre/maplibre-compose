package org.maplibre.compose.gljs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.jetbrains.skiko.wasm.onWasmReady

/**
 * Runs a browser test that hosts a real map, detached from compositing so it is never drawn. For
 * compositing on a real GPU context, see [BrowserCompositingTest].
 */
@OptIn(ExperimentalTestApi::class)
internal fun runBrowserMapTest(block: suspend ComposeUiTest.() -> Unit): Promise<*> =
  Promise<Unit> { resolve, _ -> onWasmReady { resolve(Unit) } }
    .then { runComposeUiTest(block = block) }
    .then {}

/** Detached from compositing, at a size that lays out. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.setBrowserMapContent(size: Int = 256, content: @Composable () -> Unit) {
  setContent {
    CompositionLocalProvider(LocalGlJsCompositor provides { DetachedGlJsCompositor() }) {
      Box(Modifier.size(size.dp)) { content() }
    }
  }
}

/**
 * Not [ComposeUiTest.waitUntil], which waits on the test clock: MapLibre runs on real promises and
 * timers, so each pass yields through a real `setTimeout`.
 */
@OptIn(ExperimentalTestApi::class)
internal suspend fun ComposeUiTest.waitUntilMap(
  what: String,
  timeout: Duration = 20.seconds,
  condition: () -> Boolean,
) {
  val start = Date.now()
  while (true) {
    waitForIdle()
    if (condition()) return
    if (Date.now() - start > timeout.inWholeMilliseconds) {
      throw AssertionError("Timed out after $timeout waiting for $what")
    }
    yieldToBrowser()
  }
}

/** Gives the browser's own event loop a turn, which the test dispatcher otherwise never does. */
internal suspend fun yieldToBrowser() {
  Promise<Unit> { resolve, _ -> window.setTimeout({ resolve(Unit) }, 8) }.await()
}
