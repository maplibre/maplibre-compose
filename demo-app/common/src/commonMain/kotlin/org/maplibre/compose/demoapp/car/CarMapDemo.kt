package org.maplibre.compose.demoapp.car

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.Protomaps
import org.maplibre.compose.map.MapRuntime
import org.maplibre.spatialk.geojson.Position

/** Map content and camera behavior shared by the native car hosts. */
class CarMapDemo(runtime: MapRuntime, private val scope: CoroutineScope, initialDark: Boolean) :
  AutoCloseable {
  private var cameraAnimation: Job? = null
  private var inFlightZoom: PendingZoom? = null
  private val initialCamera = CameraPosition(target = Position(-74.006, 40.7128), zoom = 12.0)
  val state =
    runtime.createMapState(baseStyle = mapStyle(initialDark).base, cameraPosition = initialCamera)
  private val cameraObserver = scope.launch {
    snapshotFlow { state.cameraMoveReason }
      .collect { reason ->
        if (reason == CameraMoveReason.GESTURE) inFlightZoom = null
      }
  }

  fun updateDarkMode(dark: Boolean) {
    checkNotNull(state.style.asMutable).baseStyle = mapStyle(dark).base
  }

  fun stop() {
    cameraAnimation?.cancel()
    inFlightZoom = null
  }

  override fun close() {
    stop()
    cameraObserver.cancel()
    state.close()
  }

  fun recenter() {
    stop()
    cameraAnimation = scope.launch { state.animateCameraPosition(initialCamera) }
  }

  fun zoom(levels: Int) {
    val from = inFlightZoom?.takeIf { it.levels == levels }?.target ?: state.cameraPosition
    val request = PendingZoom(levels, from.copy(zoom = from.zoom + levels))
    cameraAnimation?.cancel()
    inFlightZoom = request
    cameraAnimation = scope.launch {
      try {
        state.animateCameraPosition(request.target)
      } finally {
        if (inFlightZoom === request) inFlightZoom = null
      }
    }
  }

  private data class PendingZoom(val levels: Int, val target: CameraPosition)

  private fun mapStyle(dark: Boolean): Protomaps = if (dark) Protomaps.Dark else Protomaps.Light
}
