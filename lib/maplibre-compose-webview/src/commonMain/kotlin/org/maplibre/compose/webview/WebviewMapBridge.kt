@file:OptIn(ExperimentalJsExport::class)
@file:Suppress("unused")

package org.maplibre.compose.webview

import kotlinx.browser.document
import org.maplibre.kmp.js.AttributionControl
import org.maplibre.kmp.js.LogoControl
import org.maplibre.kmp.js.Map
import org.maplibre.kmp.js.MapOptions
import org.maplibre.kmp.js.NavigationControl
import org.maplibre.kmp.js.NavigationControlOptions
import org.maplibre.kmp.js.ScaleControl
import org.w3c.dom.HTMLDivElement

@JsExport
object WebviewMapBridge {
  private var container: HTMLDivElement? = null
  private lateinit var map: Map
  private lateinit var navigationControl: NavigationControl
  private lateinit var logoControl: LogoControl
  private lateinit var scaleControl: ScaleControl
  private lateinit var attributionControl: AttributionControl

  fun init() {
    container =
      document.createElement("div").also {
        it.setAttribute("style", "width: 100%; height: 100vh;")
        document.body!!.appendChild(it)
      } as HTMLDivElement
    map = Map(MapOptions(container = container!!, disableAttributionControl = true))
    navigationControl = NavigationControl(NavigationControlOptions(visualizePitch = true))
    logoControl = LogoControl()
    scaleControl = ScaleControl()
    attributionControl = AttributionControl()
  }

  fun setStyleUri(styleUri: String) {
    map.setStyle(styleUri)
  }

  fun setStyleJson(styleJson: String) {
    map.setStyle(JSON.parse(styleJson))
  }

  fun setShowCollisionBoxes(enabled: Boolean) {
    map.showCollisionBoxes = enabled
  }

  fun setShowPadding(enabled: Boolean) {
    map.showPadding = enabled
  }

  fun setShowTileBoundaries(enabled: Boolean) {
    map.showTileBoundaries = enabled
  }

  fun setShowOverdrawInspector(enabled: Boolean) {
    map.showOverdrawInspector = enabled
  }

  fun setMaxZoom(maxZoom: Double) {
    map.setMaxZoom(maxZoom)
  }

  fun setMinZoom(minZoom: Double) {
    map.setMinZoom(minZoom)
  }

  fun setMaxPitch(maxPitch: Double) {
    map.setMaxPitch(maxPitch)
  }

  fun setMinPitch(minPitch: Double) {
    map.setMinPitch(minPitch)
  }

  fun addNavigationControl(position: String) {
    map.addControl(navigationControl, position)
  }

  fun removeNavigationControl() {
    map.removeControl(navigationControl)
  }

  fun addLogoControl(position: String) {
    map.addControl(logoControl, position)
  }

  fun removeLogoControl() {
    map.removeControl(logoControl)
  }

  fun addScaleControl(position: String) {
    map.addControl(scaleControl, position)
  }

  fun removeScaleControl() {
    map.removeControl(scaleControl)
  }

  fun addAttributionControl(position: String) {
    map.addControl(attributionControl, position)
  }

  fun removeAttributionControl() {
    map.removeControl(attributionControl)
  }

  fun setTouchPitchEnabled(enabled: Boolean) {
    if (enabled) map.touchPitch.enable() else map.touchPitch.disable()
  }

  fun setDragRotateEnabled(enabled: Boolean) {
    if (enabled) map.dragRotate.enable() else map.dragRotate.disable()
  }

  fun setDragPanEnabled(enabled: Boolean) {
    if (enabled) map.dragPan.enable() else map.dragPan.disable()
  }

  fun setDoubleClickZoomEnabled(enabled: Boolean) {
    if (enabled) map.doubleClickZoom.enable() else map.doubleClickZoom.disable()
  }

  fun setScrollZoomEnabled(enabled: Boolean) {
    if (enabled) map.scrollZoom.enable() else map.scrollZoom.disable()
  }

  fun setTouchZoomRotateMode(mode: String) {
    when (mode) {
      "Disabled" -> {
        map.touchZoomRotate.disableRotation()
        map.touchZoomRotate.disable()
      }
      "ZoomOnly" -> {
        map.touchZoomRotate.enable()
        map.touchZoomRotate.disableRotation()
      }
      "RotateAndZoom" -> {
        map.touchZoomRotate.enableRotation()
        map.touchZoomRotate.enable()
      }
    }
  }

  fun setKeyboardZoomRotateMode(mode: String) {
    when (mode) {
      "Disabled" -> {
        map.keyboard.disableRotation()
        map.keyboard.disable()
      }
      "ZoomOnly" -> {
        map.keyboard.enable()
        map.keyboard.disableRotation()
      }
      "RotateAndZoom" -> {
        map.keyboard.enableRotation()
        map.keyboard.enable()
      }
    }
  }
}
