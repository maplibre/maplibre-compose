package org.maplibre.compose.location.desktop.windows

import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.location.DesktopLocationBackend
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationAccuracyAuthorization
import org.maplibre.compose.location.LocationBackendAvailability
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.inMeters
import org.maplibre.spatialk.units.extensions.meters

class WindowsLocationProviderTest {
  @Test
  fun serviceLoaderFindsWindowsBackend() {
    assertTrue(
      ServiceLoader.load(DesktopLocationBackend::class.java).any { it is WindowsLocationBackend }
    )
  }

  @Test
  fun backendIsAvailableOnlyOnWindows() {
    assertTrue(isWindows("Windows 11"))
    assertTrue(isWindows("windows server 2025"))
    assertFalse(isWindows("Linux"))
    assertFalse(isWindows("Mac OS X"))
    assertFalse(isWindows(null))
    assertEquals(isWindows(System.getProperty("os.name")), WindowsLocationBackend().isAvailable())
  }

  @Test
  fun mapsAppCapabilityAccess() {
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Unknown),
      WindowsAccessStatus.Allowed.asLocationPermission(),
    )
    assertEquals(
      LocationPermission.NotGranted(canRequest = true),
      WindowsAccessStatus.UserPromptRequired.asLocationPermission(),
    )
    listOf(
        WindowsAccessStatus.DeniedBySystem,
        WindowsAccessStatus.NotDeclared,
        WindowsAccessStatus.DeniedByUser,
      )
      .forEach {
        assertEquals(
          LocationPermission.NotGranted(canRequest = false),
          it.asLocationPermission(),
        )
      }
    assertEquals(
      LocationPermission.NotGranted(canRequest = null),
      WindowsAccessStatus.Unknown.asLocationPermission(),
    )
  }

  @Test
  fun permissionRequesterSuppressesDuplicatesAndObservesExternalChanges() {
    val client = FakeWindowsLocationClient()
    val requester = WindowsLocationPermissionRequester(client)

    requester.requestForegroundPermission()
    requester.requestForegroundPermission()
    assertEquals(1, client.accessRequests)

    client.completeAccessRequest(WindowsAccessStatus.Allowed)
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Unknown),
      requester.status.value,
    )
    requester.requestForegroundPermission()
    assertEquals(1, client.accessRequests)

    client.changeAccess(WindowsAccessStatus.DeniedByUser)
    assertEquals(LocationPermission.NotGranted(canRequest = false), requester.status.value)

    requester.close()
    requester.close()
    assertEquals(1, client.observationCloses)
    assertEquals(1, client.closeCount)
  }

  @Test
  fun permissionReadFailureHasUnknownRequestability() {
    val client = FakeWindowsLocationClient()
    client.checkFailure = IllegalStateException("native failure")
    val requester = WindowsLocationPermissionRequester(client)

    assertEquals(LocationPermission.NotGranted(canRequest = null), requester.status.value)
    requester.close()
  }

  @Test
  fun mapsAccuracyAndReportInterval() {
    assertEquals(1, LocationAccuracy.BestForNavigation.toDesiredAccuracyMeters())
    assertEquals(10, LocationAccuracy.High.toDesiredAccuracyMeters())
    assertEquals(100, LocationAccuracy.Balanced.toDesiredAccuracyMeters())
    assertEquals(1_000, LocationAccuracy.Low.toDesiredAccuracyMeters())
    assertEquals(5_000, LocationAccuracy.Lowest.toDesiredAccuracyMeters())
    assertEquals(0, 0.milliseconds.toReportIntervalMilliseconds())
    assertEquals(1, 1.milliseconds.toReportIntervalMilliseconds())
    assertEquals(1_500, 1_500.milliseconds.toReportIntervalMilliseconds())
  }

  @Test
  fun mapsPositionStatuses() {
    val granted = LocationPermission.Granted(LocationAccuracyAuthorization.Unknown)
    val denied = LocationPermission.NotGranted(canRequest = false)
    assertNull(WindowsPositionStatus.Ready.asUnavailableReason(granted))
    listOf(
        WindowsPositionStatus.Initializing,
        WindowsPositionStatus.NoData,
        WindowsPositionStatus.NotInitialized,
      )
      .forEach {
        assertEquals(
          LocationUnavailableReason.TemporarilyUnavailable,
          it.asUnavailableReason(granted),
        )
      }
    assertEquals(
      LocationUnavailableReason.ServicesDisabled,
      WindowsPositionStatus.Disabled.asUnavailableReason(granted),
    )
    assertEquals(
      LocationUnavailableReason.PermissionDenied,
      WindowsPositionStatus.Disabled.asUnavailableReason(denied),
    )
    assertEquals(
      LocationUnavailableReason.Unsupported,
      WindowsPositionStatus.NotAvailable.asUnavailableReason(granted),
    )
    assertEquals(
      LocationUnavailableReason.UnexpectedFailure,
      WindowsPositionStatus.Unknown.asUnavailableReason(granted),
    )
  }

  @Test
  fun convertsWindowsFixAndTimestamp() {
    val currentTimeMillis = 1_700_000_000_000
    val reading =
      sampleReading(
        windowsTimestampTicks =
          WINDOWS_EPOCH_TICKS + (currentTimeMillis - 2_000) * TICKS_PER_MILLISECOND
      )
    val location = checkNotNull(reading.asMapLibreLocationReading())

    assertEquals(52.0, location.position.latitude)
    assertEquals(13.0, location.position.longitude)
    assertEquals(40.0, location.position.altitude)
    assertEquals(8.0, location.horizontalAccuracy?.inMeters)
    assertEquals(3.0, location.altitudeAccuracy?.inMeters)
    assertEquals(4.0, location.speed?.inMeters)
    assertNull(location.speedAccuracy)
    assertEquals(Bearing.North + 90.degrees, location.course)
    assertNull(location.courseAccuracy)
    assertEquals(Instant.fromEpochMilliseconds(currentTimeMillis - 2_000), location.measuredAt)
  }

  @Test
  fun rejectsMalformedRequiredValuesAndOmitsMalformedOptionalValues() {
    assertNull(sampleReading(latitude = Double.NaN).asMapLibreLocationReading())
    assertNull(sampleReading(latitude = 91.0).asMapLibreLocationReading())
    assertNull(sampleReading(longitude = -181.0).asMapLibreLocationReading())
    assertNull(sampleReading(horizontalAccuracyMeters = -1.0).asMapLibreLocationReading())
    assertNull(sampleReading(windowsTimestampTicks = Long.MIN_VALUE).asMapLibreLocationReading())

    val location =
      checkNotNull(
        sampleReading(
            altitudeMeters = Double.NaN,
            verticalAccuracyMeters = -1.0,
            headingDegrees = Double.POSITIVE_INFINITY,
            speedMetersPerSecond = -1.0,
          )
          .asMapLibreLocationReading()
      )
    assertNull(location.position.altitude)
    assertNull(location.altitudeAccuracy)
    assertNull(location.course)
    assertNull(location.speed)
  }

  @Test
  fun localFilterAlwaysDeliversFirstFixAndEnforcesBothThresholds() {
    val filter = WindowsLocationFilter(1.seconds, minimumDistanceMeters = 100.0)
    val first = sampleReading(windowsTimestampTicks = 0)

    assertTrue(filter.shouldDeliver(first))
    assertFalse(
      filter.shouldDeliver(
        first.copy(longitude = 1.0, windowsTimestampTicks = 500 * TICKS_PER_MILLISECOND)
      )
    )
    assertFalse(
      filter.shouldDeliver(
        first.copy(longitude = 13.00001, windowsTimestampTicks = 2_000 * TICKS_PER_MILLISECOND)
      )
    )
    assertTrue(
      filter.shouldDeliver(
        first.copy(longitude = 13.01, windowsTimestampTicks = 2_500 * TICKS_PER_MILLISECOND)
      )
    )
  }

  @Test
  fun providerAppliesRequestAndForwardsFixesAndStatuses() = runTest {
    val client = FakeWindowsLocationClient(access = WindowsAccessStatus.Allowed)
    val provider = WindowsLocationProvider(client)
    val events = mutableListOf<LocationEvent>()
    val job =
      backgroundScope.launch(Dispatchers.Unconfined) {
        provider
          .updates(
            LocationRequest(
              accuracy = LocationAccuracy.Low,
              minimumInterval = 2.seconds,
              minimumDistance = 20.meters,
            )
          )
          .collect { events += it }
      }
    val session = client.sessions.single()
    assertEquals(1_000, session.configuration.desiredAccuracyMeters)
    assertEquals(2_000, session.configuration.reportIntervalMilliseconds)

    session.listener.onPosition(sampleReading())
    session.listener.onStatus(WindowsPositionStatus.NoData)
    session.listener.onFailure(IllegalStateException("native failure"))

    assertIs<LocationEvent.Update>(events[0])
    assertEquals(
      LocationUnavailableReason.TemporarilyUnavailable,
      assertIs<LocationEvent.Unavailable>(events[1]).reason,
    )
    assertEquals(
      LocationUnavailableReason.UnexpectedFailure,
      assertIs<LocationEvent.Unavailable>(events[2]).reason,
    )
    assertIs<IllegalStateException>(assertIs<LocationEvent.Unavailable>(events[2]).cause)

    job.cancelAndJoin()
    assertEquals(1, session.closeCount)
    provider.close()
    assertEquals(1, session.closeCount)
    assertEquals(1, client.closeCount)
  }

  @Test
  fun collectorsOwnIndependentSessionsAndProviderClosesEachExactlyOnce() = runTest {
    val client = FakeWindowsLocationClient(access = WindowsAccessStatus.Allowed)
    val provider = WindowsLocationProvider(client)
    val first =
      backgroundScope.launch(Dispatchers.Unconfined) {
        provider.updates(LocationRequest()).collect {}
      }
    val second =
      backgroundScope.launch(Dispatchers.Unconfined) {
        provider.updates(LocationRequest()).collect {}
      }

    assertEquals(2, client.sessions.size)
    assertTrue(client.sessions[0] !== client.sessions[1])
    provider.close()
    provider.close()
    assertTrue(client.sessions.all { it.closeCount == 1 })
    assertEquals(1, client.observationCloses)
    assertEquals(1, client.closeCount)

    first.cancelAndJoin()
    second.cancelAndJoin()
    assertTrue(client.sessions.all { it.closeCount == 1 })
  }

  @Test
  fun misconfiguredBackendEmitsMisconfiguredWithoutOpeningSession() = runTest {
    val cause = IllegalStateException("activation failed")
    val client =
      FakeWindowsLocationClient(
        backendAvailability = LocationBackendAvailability.Misconfigured(cause)
      )
    val provider = WindowsLocationProvider(client)

    val event = assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())
    assertEquals(LocationUnavailableReason.Misconfigured, event.reason)
    assertEquals(cause, event.cause)
    assertTrue(client.sessions.isEmpty())
  }

  @Test
  fun sessionCreationFailureIsUnexpected() = runTest {
    val client = FakeWindowsLocationClient(access = WindowsAccessStatus.Allowed)
    client.sessionFailure = IllegalStateException("native failure")
    val provider = WindowsLocationProvider(client)

    val event = assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())
    assertEquals(LocationUnavailableReason.UnexpectedFailure, event.reason)
    assertIs<IllegalStateException>(event.cause)
  }
}

private class FakeWindowsLocationClient(
  var access: WindowsAccessStatus = WindowsAccessStatus.UserPromptRequired,
  override val backendAvailability: LocationBackendAvailability =
    LocationBackendAvailability.Available,
) : WindowsLocationClient {
  val sessions = mutableListOf<FakeWindowsSession>()
  var accessRequests = 0
  var observationCloses = 0
  var closeCount = 0
  var checkFailure: Throwable? = null
  var sessionFailure: Throwable? = null
  private var accessObserver: ((WindowsAccessStatus) -> Unit)? = null
  private var accessCompletion: ((WindowsAccessStatus) -> Unit)? = null

  override fun checkAccess(): WindowsAccessStatus {
    checkFailure?.let { throw it }
    return access
  }

  override fun observeAccess(onChanged: (WindowsAccessStatus) -> Unit): WindowsCloseable {
    accessObserver = onChanged
    var closed = false
    return WindowsCloseable {
      if (!closed) {
        closed = true
        observationCloses++
        accessObserver = null
      }
    }
  }

  override fun requestAccess(onCompleted: (WindowsAccessStatus) -> Unit) {
    accessRequests++
    accessCompletion = onCompleted
  }

  override fun createSession(
    configuration: WindowsLocationConfiguration,
    listener: WindowsLocationListener,
  ): WindowsCloseable {
    sessionFailure?.let { throw it }
    val session = FakeWindowsSession(configuration, listener)
    sessions += session
    return WindowsCloseable(session::close)
  }

  override fun close() {
    closeCount++
  }

  fun completeAccessRequest(result: WindowsAccessStatus) {
    access = result
    accessCompletion?.invoke(result)
    accessCompletion = null
  }

  fun changeAccess(result: WindowsAccessStatus) {
    access = result
    accessObserver?.invoke(result)
  }
}

private class FakeWindowsSession(
  val configuration: WindowsLocationConfiguration,
  val listener: WindowsLocationListener,
) {
  var closeCount = 0

  fun close() {
    closeCount++
  }
}

private fun sampleReading(
  latitude: Double = 52.0,
  longitude: Double = 13.0,
  altitudeMeters: Double? = 40.0,
  horizontalAccuracyMeters: Double = 8.0,
  verticalAccuracyMeters: Double? = 3.0,
  headingDegrees: Double? = 90.0,
  speedMetersPerSecond: Double? = 4.0,
  windowsTimestampTicks: Long = WINDOWS_EPOCH_TICKS + 1_700_000_000_000 * TICKS_PER_MILLISECOND,
): WindowsLocationReading =
  WindowsLocationReading(
    latitude,
    longitude,
    altitudeMeters,
    horizontalAccuracyMeters,
    verticalAccuracyMeters,
    headingDegrees,
    speedMetersPerSecond,
    windowsTimestampTicks,
  )
