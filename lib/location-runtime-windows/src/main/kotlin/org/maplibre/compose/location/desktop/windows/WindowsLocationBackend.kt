package org.maplibre.compose.location.desktop.windows

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import org.maplibre.compose.location.DesktopLocationBackend
import org.maplibre.compose.location.DesktopLocationProvider
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationAccuracyAuthorization
import org.maplibre.compose.location.LocationBackendAvailability
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.XdgPortalWindow
import org.maplibre.spatialk.units.extensions.inMeters

/** Windows desktop location backend backed by Windows Runtime geolocation. */
public class WindowsLocationBackend : DesktopLocationBackend {
  override val id: String = "windows-geolocation"

  override fun isAvailable(): Boolean = isWindows(System.getProperty("os.name"))

  override fun createProvider(window: XdgPortalWindow?): DesktopLocationProvider =
    WindowsLocationProvider()
}

/**
 * Foreground location from Windows geolocation.
 *
 * Requests accuracy of 1, 10, 100, 1,000, or 5,000 meters for [LocationAccuracy.BestForNavigation]
 * through [LocationAccuracy.Lowest]. Applies [LocationRequest.minimumInterval] and
 * [LocationRequest.minimumDistance] after the first valid measurement.
 *
 * An initializing service or missing data reports
 * [LocationUnavailableReason.TemporarilyUnavailable]. A disabled service reports
 * [LocationUnavailableReason.PermissionDenied] when access is denied, and
 * [LocationUnavailableReason.ServicesDisabled] otherwise. Unavailable hardware reports
 * [LocationUnavailableReason.Unsupported]. Other failures report
 * [LocationUnavailableReason.UnexpectedFailure].
 */
public class WindowsLocationProvider
internal constructor(private val client: WindowsLocationClient) : DesktopLocationProvider {
  public constructor() : this(SystemWindowsLocationClient())

  private val requester = WindowsLocationPermissionRequester(client, ownsClient = false)
  private val lifecycleLock = Any()
  private val sessions = mutableSetOf<Session>()
  private var closed = false
  // Keep the client alive across native calls without holding the lifecycle lock in callbacks.
  private var activeOperations = 0
  private var clientClosed = false

  override val backendAvailability: LocationBackendAvailability = client.backendAvailability

  override val permission: StateFlow<LocationPermission>
    get() = requester.status

  override fun requestPermission() {
    synchronized(lifecycleLock) {
      check(!closed) { "The Windows location provider is closed" }
      activeOperations++
    }
    try {
      requester.requestForegroundPermission()
    } finally {
      finishOperation()
    }
  }

  override fun updates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
    check(backendAvailability == LocationBackendAvailability.Available) {
      "Location updates require an available backend: $backendAvailability"
    }

    val filter = WindowsLocationFilter(request.minimumInterval, request.minimumDistance.inMeters)
    val listener =
      object : WindowsLocationListener {
        override fun onPosition(measurement: WindowsLocationMeasurement) {
          val location = measurement.asMapLibreLocationMeasurement() ?: return
          synchronized(filter) {
            if (filter.shouldDeliver(measurement)) {
              trySend(
                LocationEvent.Update(
                  measurement = location,
                  measurementMark = TimeSource.Monotonic.markNow(),
                )
              )
            }
          }
        }

        override fun onStatus(status: WindowsPositionStatus) {
          status.asUnavailableReason(permission.value)?.let {
            trySend(LocationEvent.Unavailable(it))
          }
        }

        override fun onFailure(error: Throwable) {
          trySend(LocationEvent.Unavailable(LocationUnavailableReason.UnexpectedFailure, error))
        }
      }
    val session = Session { close() }
    synchronized(lifecycleLock) {
      check(!closed) { "The Windows location provider is closed" }
      sessions += session
      activeOperations++
    }
    try {
      val nativeSession =
        client.createSession(
          WindowsLocationConfiguration(
            desiredAccuracyMeters = request.accuracy.toDesiredAccuracyMeters(),
            reportIntervalMilliseconds = request.minimumInterval.toReportIntervalMilliseconds(),
          ),
          listener,
        )
      val retained =
        synchronized(lifecycleLock) {
          if (session in sessions) {
            session.nativeSession = nativeSession
            true
          } else {
            false
          }
        }
      if (!retained) nativeSession.close()
    } catch (error: Throwable) {
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.UnexpectedFailure, error))
      closeSession(session)
    } finally {
      finishOperation()
    }
    awaitClose { closeSession(session) }
  }

  override fun close() {
    val activeSessions =
      synchronized(lifecycleLock) {
        if (closed) return
        closed = true
        activeOperations++
        sessions.toList()
      }
    try {
      activeSessions.forEach(::closeSession)
    } finally {
      finishOperation()
    }
  }

  private fun closeSession(session: Session) {
    val nativeSession =
      synchronized(lifecycleLock) {
        if (!sessions.remove(session)) return
        activeOperations++
        session.nativeSession
      }
    try {
      session.closeFlow()
      nativeSession?.let { runCatching(it::close) }
    } finally {
      finishOperation()
    }
  }

  private fun finishOperation() {
    val releaseClient =
      synchronized(lifecycleLock) {
        activeOperations--
        if (closed && activeOperations == 0 && sessions.isEmpty() && !clientClosed) {
          clientClosed = true
          true
        } else {
          false
        }
      }
    if (releaseClient) {
      runCatching(requester::close)
      runCatching(client::close)
    }
  }

  private class Session(val closeFlow: () -> Unit) {
    var nativeSession: WindowsCloseable? = null
  }
}

/**
 * Foreground Windows location permission holder.
 *
 * [WindowsLocationProvider] delegates [LocationProvider.permission] and
 * [LocationProvider.requestPermission] to this class. Custom providers can use it directly.
 * `AppCapability.Create("location").CheckAccess()` maps `Allowed` to [LocationPermission.Granted]
 * with [LocationAccuracyAuthorization.Unknown], `UserPromptRequired` to a requestable
 * [LocationPermission.NotGranted], user or system denial and a missing packaged capability to a
 * non-requestable value, and unknown failures to `canRequest = null`. `AccessChanged` keeps
 * [status] synchronized with changes made in Windows Settings.
 *
 * [requestForegroundPermission] suppresses duplicate requests and starts
 * `Geolocator.RequestAccessAsync()` on the AWT event-dispatch thread.
 */
public class WindowsLocationPermissionRequester
internal constructor(
  private val client: WindowsLocationClient,
  private val ownsClient: Boolean = true,
) : AutoCloseable {
  public constructor() : this(SystemWindowsLocationClient())

  /** Whether the process has a usable Windows Runtime location implementation. */
  public val backendAvailability: LocationBackendAvailability = client.backendAvailability
  private val mutableStatus =
    MutableStateFlow<LocationPermission>(LocationPermission.NotGranted(canRequest = null))

  /** Current Windows location access, including changes made outside the application. */
  public val status: StateFlow<LocationPermission> = mutableStatus
  private val requestPending = AtomicBoolean()
  private val closed = AtomicBoolean()
  private var accessObservation: WindowsCloseable? = null

  init {
    if (backendAvailability == LocationBackendAvailability.Available) {
      mutableStatus.value = readAccess()
      accessObservation =
        try {
          client.observeAccess { mutableStatus.value = it.asLocationPermission() }
        } catch (_: Throwable) {
          mutableStatus.value = LocationPermission.NotGranted(canRequest = null)
          null
        }
    }
  }

  /** Starts a foreground permission request and publishes its result to [status]. */
  public fun requestForegroundPermission() {
    if (closed.get() || backendAvailability != LocationBackendAvailability.Available) return
    if (status.value != LocationPermission.NotGranted(canRequest = true)) return
    if (!requestPending.compareAndSet(false, true)) return
    try {
      client.requestAccess {
        mutableStatus.value = it.asLocationPermission()
        requestPending.set(false)
      }
    } catch (_: Throwable) {
      mutableStatus.value = LocationPermission.NotGranted(canRequest = null)
      requestPending.set(false)
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    try {
      accessObservation?.close()
    } finally {
      accessObservation = null
      if (ownsClient) client.close()
    }
  }

  private fun readAccess(): LocationPermission =
    try {
      client.checkAccess().asLocationPermission()
    } catch (_: Throwable) {
      LocationPermission.NotGranted(canRequest = null)
    }
}

internal fun isWindows(osName: String?): Boolean =
  osName?.lowercase(Locale.ROOT)?.startsWith("windows") == true
