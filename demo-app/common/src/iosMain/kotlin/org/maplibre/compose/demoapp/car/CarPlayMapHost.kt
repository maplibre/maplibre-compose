@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.maplibre.compose.demoapp.car

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.maplibre.compose.map.AppleMapPresentation
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MaplibreMapView
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.overlay.attributions
import platform.UIKit.UIView

/** Exposes the shared map demo to the application's Swift CarPlay scene. */
class CarPlayMapHost(initialDark: Boolean) : AutoCloseable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val demo = CarMapDemo(DefaultMapRuntime.instance, scope, initialDark)
  private val presentation = AppleMapPresentation(demo.state, isActive = false)
  val view: UIView = MaplibreMapView(presentation)
  val credits: List<String>
    get() = demo.state.style.attributions()

  val status: String
    get() =
      when {
        presentation.failure != null || demo.state.style.loadState is StyleLoadState.Failed ->
          "Map unavailable"
        demo.state.style.loadState != StyleLoadState.Ready -> "Loading map…"
        else -> "MapLibre"
      }

  var onChange: (() -> Unit)? = null
  private var closed = false

  init {
    scope.launch {
      snapshotFlow { Pair(status, credits) }.collect { onChange?.invoke() }
    }
  }

  fun recenter() = demo.recenter()

  fun zoom(levels: Int) = demo.zoom(levels)

  fun pan(x: Double, y: Double) = demo.state.panBy(DpOffset(x.toFloat().dp, y.toFloat().dp))

  fun fling(x: Double, y: Double) = demo.state.fling(DpOffset(x.toFloat().dp, y.toFloat().dp))

  fun scale(factor: Double, x: Double, y: Double) =
    demo.state.scaleBy(factor, DpOffset(x.toFloat().dp, y.toFloat().dp))

  fun setActive(active: Boolean) {
    if (closed || presentation.failure != null) return
    presentation.isActive = active
    if (!active) demo.stop()
  }

  fun updateDarkMode(dark: Boolean) = demo.updateDarkMode(dark)

  fun updateInsets(top: Double, left: Double, bottom: Double, right: Double) {
    if (closed || presentation.failure != null) return
    presentation.cameraPadding =
      PaddingValues.Absolute(
        left = left.toFloat().dp,
        top = top.toFloat().dp,
        right = right.toFloat().dp,
        bottom = bottom.toFloat().dp,
      )
  }

  override fun close() {
    if (closed) return
    closed = true
    onChange = null
    view.removeFromSuperview()
    presentation.close()
    demo.close()
    scope.cancel()
  }
}
