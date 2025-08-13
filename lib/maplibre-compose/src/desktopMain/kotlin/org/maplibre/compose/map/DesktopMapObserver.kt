package org.maplibre.compose.map

import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.kmp.native.camera.CameraChangeMode
import org.maplibre.kmp.native.map.MapLoadError
import org.maplibre.kmp.native.map.MapObserver

internal class DesktopMapObserver(internal var callbacks: MapAdapter.Callbacks) : MapObserver {
  internal lateinit var adapter: DesktopMapAdapter

  override fun onDidFinishLoadingMap() {
    callbacks.onMapFinishedLoading(adapter)
  }

  override fun onDidFailLoadingMap(error: MapLoadError, message: String) {
    callbacks.onMapFailLoading(message)
  }

  override fun onCameraWillChange(mode: CameraChangeMode) {
    // TODO: Determine the reason for camera movement
    callbacks.onCameraMoveStarted(adapter, CameraMoveReason.UNKNOWN)
  }

  override fun onCameraIsChanging() {
    callbacks.onCameraMoved(adapter)
  }

  override fun onCameraDidChange(mode: CameraChangeMode) {
    callbacks.onCameraMoveEnded(adapter)
  }

  // TODO map click listener
  // TODO map long (right) click listener
  // TODO map fps changed (render stats?) listener
}
