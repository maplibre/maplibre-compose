@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.maplibre.compose.benchmark.AppleMapFrames
import org.maplibre.compose.benchmark.BenchmarkUiFrames
import platform.AppKit.NSApplication
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.QuartzCore.CAMetalLayer

@Composable
internal actual fun rememberAppleMapFrames(): BenchmarkUiFrames = remember {
  AppleMapFrames {
    val app = NSApplication.sharedApplication()
    val window = app.keyWindow ?: app.windows.filterIsInstance<NSWindow>().single { it.isVisible() }
    // MapLibre owns a plain NSView with a Metal layer; Compose uses a Skiko NSView subclass.
    val view =
      descendants(checkNotNull(window.contentView))
        .filter { it.isMemberOfClass(NSView) && it.layer is CAMetalLayer }
        .single()
    (view.layer as CAMetalLayer) to checkNotNull(window.screen)
  }
}

private fun descendants(view: NSView): List<NSView> =
  listOf(view) + view.subviews.filterIsInstance<NSView>().flatMap(::descendants)
