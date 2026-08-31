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
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.map.GlJsMapSession
import org.maplibre.compose.map.MapState

/**
 * The worker URL Karma serves the suite, copied next to the test bundle by
 * `karma.config.d/maplibre-gl-worker.js`. The suite overrides the library's CDN default with this
 * so tests never reach the network.
 */
internal const val LOCAL_WORKER_URL: String = "/maplibre-gl-worker.mjs"

/**
 * Runs a browser test that hosts a real map, detached from compositing so it is never drawn. For
 * compositing on a real GPU context, see [BrowserCompositingTest].
 */
@OptIn(ExperimentalTestApi::class)
internal fun runBrowserMapTest(block: suspend ComposeUiTest.() -> Unit): Promise<*> =
  Promise<Unit> { resolve, _ -> onWasmReady { resolve(Unit) } }
    .then {
      // Set before any map is built: pointAtWorker keeps the first call, so this local URL wins and
      // the CDN default never makes the suite reach the network.
      GlJsRuntime.pointAtWorker(LOCAL_WORKER_URL)
      runComposeUiTest(block = block)
    }
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
 *
 * Compose-hosted maps use [DetachedGlJsCompositor] and have no
 * [org.maplibre.compose.testing.GlJsMapFixture]. [pump] may draw one [GlJsFrameTarget.Detached]
 * frame from a published [GlJsMapSession] at the last Compose-applied extent. Timeout is still
 * measured. The failure is an [AssertionError] that reports the frame count and [diagnostics].
 */
@OptIn(ExperimentalTestApi::class)
internal suspend fun ComposeUiTest.waitUntilMap(
  what: String,
  timeout: Duration = 20.seconds,
  diagnostics: () -> String = { "" },
  pump: () -> Unit = {},
  condition: () -> Boolean,
) {
  val start = Date.now()
  var frames = 0
  while (true) {
    waitForIdle()
    if (condition()) return
    if (Date.now() - start > timeout.inWholeMilliseconds) {
      val dump = diagnostics().trim()
      val suffix = if (dump.isEmpty()) "" else ". $dump"
      throw AssertionError("Timed out after $frames frames waiting for $what$suffix")
    }
    pump()
    frames++
    yieldToBrowser()
  }
}

/**
 * Presentation nullness, style load state, close state, and the current engine map when the
 * presentation is a [GlJsMapSession].
 */
internal fun mapWaitDiagnostics(state: MapState?, extra: String = ""): String {
  val presentation = state?.presentation
  val session = presentation?.adapter as? GlJsMapSession
  val parts = buildList {
    add("presentation=${if (presentation == null) "null" else "attached"}")
    if (presentation != null) add("valid=${presentation.isValid}")
    add("style=${state?.style?.loadState}")
    add("closed=${state?.isClosed}")
    if (session != null) {
      add("engine=${if (session.engineMapForTest() == null) "null" else "live"}")
      add("canPresentFrames=${session.canPresentFrames}")
    }
    if (extra.isNotEmpty()) add(extra)
  }
  return parts.joinToString(", ")
}

/** No-op until Compose has published a [GlJsMapSession] with a non-empty extent. */
internal fun pumpPublishedDetachedFrame(state: MapState?) {
  val session = state?.presentation?.adapter as? GlJsMapSession ?: return
  session.renderDetachedIfReady()
}

internal fun CameraPosition.isNear(other: CameraPosition): Boolean =
  target.longitude.isNear(other.target.longitude) &&
    target.latitude.isNear(other.target.latitude) &&
    zoom.isNear(other.zoom) &&
    bearing.isNear(other.bearing) &&
    tilt.isNear(other.tilt)

private fun Double.isNear(other: Double): Boolean = kotlin.math.abs(this - other) < 0.001

/** Gives the browser's own event loop a turn, which the test dispatcher otherwise never does. */
internal suspend fun yieldToBrowser() {
  Promise<Unit> { resolve, _ -> window.setTimeout({ resolve(Unit) }, 8) }.await()
}
