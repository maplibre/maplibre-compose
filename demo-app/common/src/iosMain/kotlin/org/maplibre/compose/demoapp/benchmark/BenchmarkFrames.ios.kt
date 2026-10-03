@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.maplibre.compose.demoapp.benchmark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import org.maplibre.compose.benchmark.AppleMapFrames
import org.maplibre.compose.benchmark.BenchmarkUiFrames
import org.maplibre.compose.map.MaplibreMapView
import platform.QuartzCore.CAMetalLayer
import platform.UIKit.UIView

@Composable
internal actual fun rememberAppleMapFrames(): BenchmarkUiFrames {
  val controller = LocalUIViewController.current
  return remember(controller) {
    AppleMapFrames {
      val view = descendants(controller.view).filterIsInstance<MaplibreMapView>().single()
      (view.layer as CAMetalLayer) to checkNotNull(view.window).screen
    }
  }
}

private fun descendants(view: UIView): List<UIView> =
  listOf(view) + view.subviews.filterIsInstance<UIView>().flatMap(::descendants)
