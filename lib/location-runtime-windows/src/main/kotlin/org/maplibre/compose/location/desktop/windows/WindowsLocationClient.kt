package org.maplibre.compose.location.desktop.windows

import org.maplibre.compose.location.LocationBackendAvailability

internal fun interface WindowsCloseable : AutoCloseable {
  override fun close()
}

internal interface WindowsLocationListener {
  fun onPosition(measurement: WindowsLocationMeasurement)

  fun onStatus(status: WindowsPositionStatus)

  fun onFailure(error: Throwable)
}

internal interface WindowsLocationClient : AutoCloseable {
  val backendAvailability: LocationBackendAvailability

  fun checkAccess(): WindowsAccessStatus

  fun observeAccess(onChanged: (WindowsAccessStatus) -> Unit): WindowsCloseable

  fun requestAccess(onCompleted: (WindowsAccessStatus) -> Unit)

  fun createSession(
    configuration: WindowsLocationConfiguration,
    listener: WindowsLocationListener,
  ): WindowsCloseable
}
