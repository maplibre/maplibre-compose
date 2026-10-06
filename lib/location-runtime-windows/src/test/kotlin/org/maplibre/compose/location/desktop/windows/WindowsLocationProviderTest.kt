package org.maplibre.compose.location.desktop.windows

import java.util.ServiceLoader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.location.DesktopLocationBackend
import org.maplibre.compose.location.LocationAccuracy
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
  fun permissionRequesterSuppressesDuplicatesAndObservesExternalChanges() {
    val client = FakeWindowsLocationClient()
    val requester = WindowsLocationPermissionRequester(client)

    requester.requestForegroundPermission()
    requester.requestForegroundPermission()
    assertEquals(1, client.accessRequests)

    client.completeAccessRequest(WindowsAccessStatus.Allowed)
    assertEquals(
      LocationPermission.Granted(accuracy = null),
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
  fun disabledPositionStatusDependsOnPermission() {
    assertEquals(
      LocationUnavailableReason.ServicesDisabled,
      WindowsPositionStatus.Disabled.asUnavailableReason(
        LocationPermission.Granted(accuracy = null)
      ),
    )
    assertEquals(
      LocationUnavailableReason.PermissionDenied,
      WindowsPositionStatus.Disabled.asUnavailableReason(
        LocationPermission.NotGranted(canRequest = false)
      ),
    )
  }

  @Test
  fun convertsWindowsFixAndTimestamp() {
    val measurement = sampleMeasurement(windowsTimestampTicks = 133_444_735_980_000_000L)
    val location = checkNotNull(measurement.asMaplibreLocationMeasurement())

    assertEquals(52.0, location.position.latitude)
    assertEquals(13.0, location.position.longitude)
    assertEquals(40.0, location.position.altitude)
    assertEquals(8.0, location.horizontalAccuracy?.inMeters)
    assertEquals(3.0, location.altitudeAccuracy?.inMeters)
    assertEquals(4.0, location.distancePerSecond?.inMeters)
    assertNull(location.distancePerSecondAccuracy)
    assertEquals(Bearing.North + 90.degrees, location.course)
    assertNull(location.courseAccuracy)
    assertEquals(Instant.parse("2023-11-14T22:13:18Z"), location.measuredAt)
  }

  @Test
  fun rejectsMalformedRequiredValuesAndOmitsMalformedOptionalValues() {
    assertNull(sampleMeasurement(latitude = Double.NaN).asMaplibreLocationMeasurement())
    assertNull(sampleMeasurement(latitude = 91.0).asMaplibreLocationMeasurement())
    assertNull(sampleMeasurement(longitude = -181.0).asMaplibreLocationMeasurement())
    assertNull(sampleMeasurement(horizontalAccuracyMeters = -1.0).asMaplibreLocationMeasurement())
    assertNull(
      sampleMeasurement(windowsTimestampTicks = Long.MIN_VALUE).asMaplibreLocationMeasurement()
    )

    val location =
      checkNotNull(
        sampleMeasurement(
            altitudeMeters = Double.NaN,
            verticalAccuracyMeters = -1.0,
            headingDegrees = Double.POSITIVE_INFINITY,
            speedMetersPerSecond = -1.0,
          )
          .asMaplibreLocationMeasurement()
      )
    assertNull(location.position.altitude)
    assertNull(location.altitudeAccuracy)
    assertNull(location.course)
    assertNull(location.distancePerSecond)
  }

  @Test
  fun localFilterAlwaysDeliversFirstFixAndEnforcesBothThresholds() {
    val filter = WindowsLocationFilter(1.seconds, minimumDistanceMeters = 100.0)
    val first = sampleMeasurement(windowsTimestampTicks = 0)

    assertTrue(filter.shouldDeliver(first))
    assertFalse(
      filter.shouldDeliver(
        first.copy(longitude = 1.0, windowsTimestampTicks = 500 * TicksPerMillisecond)
      )
    )
    assertFalse(
      filter.shouldDeliver(
        first.copy(longitude = 13.00001, windowsTimestampTicks = 2_000 * TicksPerMillisecond)
      )
    )
    assertTrue(
      filter.shouldDeliver(
        first.copy(longitude = 13.01, windowsTimestampTicks = 2_500 * TicksPerMillisecond)
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

    session.listener.onPosition(sampleMeasurement())
    session.listener.onStatus(WindowsPositionStatus.Ready)
    session.listener.onStatus(WindowsPositionStatus.NoData)
    session.listener.onFailure(IllegalStateException("native failure"))
    session.listener.onStatus(WindowsPositionStatus.Unknown)

    assertEquals(4, events.size)
    assertIs<LocationEvent.Update>(events[0])
    assertEquals(
      LocationUnavailableReason.TemporarilyUnavailable,
      assertIs<LocationEvent.Unavailable>(events[1]).reason,
    )
    assertNull(assertIs<LocationEvent.Unavailable>(events[2]).reason)
    assertIs<IllegalStateException>(assertIs<LocationEvent.Unavailable>(events[2]).cause)
    assertNull(assertIs<LocationEvent.Unavailable>(events[3]).reason)

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

    first.join()
    second.join()
    assertTrue(first.isCompleted)
    assertTrue(second.isCompleted)
    assertTrue(client.sessions.all { it.closeCount == 1 })
  }

  @Test
  fun cancellationDuringSessionCreationClosesTheReturnedSession() = runTest {
    val creationStarted = CountDownLatch(1)
    val finishCreation = CountDownLatch(1)
    val client = FakeWindowsLocationClient(access = WindowsAccessStatus.Allowed)
    client.onCreateSession = {
      creationStarted.countDown()
      check(finishCreation.await(5, TimeUnit.SECONDS))
    }
    val provider = WindowsLocationProvider(client)
    val collector =
      backgroundScope.launch(Dispatchers.Default) {
        provider.updates(LocationRequest()).collect {}
      }

    try {
      assertTrue(creationStarted.await(5, TimeUnit.SECONDS))
      collector.cancel()
    } finally {
      finishCreation.countDown()
    }
    collector.join()

    assertEquals(1, client.sessions.single().closeCount)
    assertEquals(0, client.closeCount)
    provider.close()
    assertEquals(1, client.sessions.single().closeCount)
    assertEquals(1, client.closeCount)
  }

  @Test
  fun sessionCreationCanCloseProviderReentrantly() = runTest {
    val client = FakeWindowsLocationClient(access = WindowsAccessStatus.Allowed)
    val provider = WindowsLocationProvider(client)
    client.onCreateSession = {
      provider.close()
      assertEquals(0, client.closeCount)
    }
    val collector =
      backgroundScope.launch(Dispatchers.Unconfined) {
        provider.updates(LocationRequest()).collect {}
      }
    collector.join()

    assertFalse(collector.isCancelled)
    assertEquals(1, client.sessions.single().closeCount)
    assertEquals(1, client.closeCount)
  }

  @Test
  fun closedProviderRejectsNewWork() = runTest {
    val client = FakeWindowsLocationClient()
    val provider = WindowsLocationProvider(client)
    provider.close()

    assertFailsWith<IllegalStateException> { provider.requestPermission() }
    assertFailsWith<IllegalStateException> { provider.updates(LocationRequest()).first() }
    assertEquals(0, client.accessRequests)
    assertTrue(client.sessions.isEmpty())
    assertEquals(1, client.closeCount)
  }

  @Test
  fun misconfiguredBackendRejectsUpdatesWithoutOpeningSession() = runTest {
    val cause = IllegalStateException("activation failed")
    val client =
      FakeWindowsLocationClient(
        backendAvailability = LocationBackendAvailability.Misconfigured(cause)
      )
    val provider = WindowsLocationProvider(client)

    assertFailsWith<IllegalStateException> { provider.updates(LocationRequest()).first() }
    assertTrue(client.sessions.isEmpty())
  }

  @Test
  fun sessionCreationFailureIsUnexpected() = runTest {
    val client = FakeWindowsLocationClient(access = WindowsAccessStatus.Allowed)
    client.sessionFailure = IllegalStateException("native failure")
    val provider = WindowsLocationProvider(client)

    val event = assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())
    assertNull(event.reason)
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
  var onCreateSession: (() -> Unit)? = null
  private var accessObserver: ((WindowsAccessStatus) -> Unit)? = null
  private var accessCompletion: ((WindowsAccessStatus) -> Unit)? = null

  override fun checkAccess(): WindowsAccessStatus {
    checkFailure?.let { throw it }
    return access
  }

  override fun observeAccess(onChanged: (WindowsAccessStatus) -> Unit): AutoCloseable {
    accessObserver = onChanged
    var closed = false
    return AutoCloseable {
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
  ): AutoCloseable {
    onCreateSession?.invoke()
    sessionFailure?.let { throw it }
    val session = FakeWindowsSession(configuration, listener)
    sessions += session
    return AutoCloseable(session::close)
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

private fun sampleMeasurement(
  latitude: Double = 52.0,
  longitude: Double = 13.0,
  altitudeMeters: Double? = 40.0,
  horizontalAccuracyMeters: Double = 8.0,
  verticalAccuracyMeters: Double? = 3.0,
  headingDegrees: Double? = 90.0,
  speedMetersPerSecond: Double? = 4.0,
  windowsTimestampTicks: Long = WindowsEpochTicks + 1_700_000_000_000 * TicksPerMillisecond,
): WindowsLocationMeasurement =
  WindowsLocationMeasurement(
    latitude,
    longitude,
    altitudeMeters,
    horizontalAccuracyMeters,
    verticalAccuracyMeters,
    headingDegrees,
    speedMetersPerSecond,
    windowsTimestampTicks,
  )
