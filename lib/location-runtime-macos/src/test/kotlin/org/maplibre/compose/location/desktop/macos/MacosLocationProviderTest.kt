package org.maplibre.compose.location.desktop.macos

import java.util.Collections
import java.util.Locale
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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

@OptIn(ExperimentalCoroutinesApi::class)
class MacosLocationProviderTest {
  @Test
  fun collectorRecoversAfterPermissionChanges() = runTest {
    val client = FakeCoreLocationClient(authorizationStatus = CL_AUTHORIZATION_DENIED)
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined, Dispatchers.Unconfined)
    val permissionManager = client.managers.single()
    val events = mutableListOf<LocationEvent>()
    val collection = backgroundScope.launch {
      provider.updates(LocationRequest()).collect(events::add)
    }
    runCurrent()
    assertEquals(
      LocationUnavailableReason.PermissionDenied,
      assertIs<LocationEvent.Unavailable>(events.last()).reason,
    )
    assertEquals(1, client.managers.size)

    permissionManager.authorizationStatus = CL_AUTHORIZATION_AUTHORIZED_WHEN_IN_USE
    permissionManager.boundDelegate?.didChangeAuthorization()
    runCurrent()
    val firstManagers = client.managers.drop(1)
    assertEquals(1, firstManagers.size)
    firstManagers.forEach { it.boundDelegate?.didUpdateLocations(listOf(sampleMeasurement())) }
    runCurrent()
    assertIs<LocationEvent.Update>(events.last())

    permissionManager.authorizationStatus = CL_AUTHORIZATION_DENIED
    permissionManager.boundDelegate?.didChangeAuthorization()
    runCurrent()
    assertTrue(firstManagers.all { it.closed })
    assertEquals(
      LocationUnavailableReason.PermissionDenied,
      assertIs<LocationEvent.Unavailable>(events.last()).reason,
    )
    permissionManager.authorizationStatus = CL_AUTHORIZATION_AUTHORIZED_WHEN_IN_USE
    permissionManager.boundDelegate?.didChangeAuthorization()
    runCurrent()
    assertEquals(3, client.managers.size)
    client.managers.last().boundDelegate?.didUpdateLocations(listOf(sampleMeasurement()))
    runCurrent()
    assertIs<LocationEvent.Update>(events.last())
    collection.cancel()
    runCurrent()
    assertTrue(client.managers.last().closed)
    assertTrue(client.managers.all { it.whenInUseRequests == 0 })
    provider.close()
  }

  @Test
  fun serviceLoaderFindsMacosBackend() {
    assertTrue(
      ServiceLoader.load(DesktopLocationBackend::class.java).any { it is MacosLocationBackend }
    )
  }

  @Test
  fun mapsCoreLocationErrorsByRecoverability() {
    val denied = CoreLocationError(CL_ERROR_DOMAIN, CL_ERROR_DENIED)
    assertEquals(LocationUnavailableReason.ServicesDisabled, denied.asUnavailableReason(false))
    assertEquals(LocationUnavailableReason.PermissionDenied, denied.asUnavailableReason(true))
    assertEquals(
      LocationUnavailableReason.UnexpectedFailure,
      CoreLocationError("example.error", CL_ERROR_DENIED).asUnavailableReason(true),
    )
  }

  @Test
  fun mapsAccuracyAuthorizationOfGrants() {
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Precise),
      readPermission(CL_AUTHORIZATION_AUTHORIZED_ALWAYS, CL_ACCURACY_AUTHORIZATION_FULL),
    )
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Approximate),
      readPermission(CL_AUTHORIZATION_AUTHORIZED_WHEN_IN_USE, 1),
    )
  }

  @Test
  fun lowestAccuracyUsesExportedReducedAccuracy() {
    assertEquals(CL_LOCATION_ACCURACY_REDUCED, LocationAccuracy.Lowest.toDesiredAccuracy())
    if (System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("mac")) {
      assertEquals(
        ObjectiveC.exportedDoubleOrNull("kCLLocationAccuracyReduced"),
        CL_LOCATION_ACCURACY_REDUCED,
      )
    }
  }

  @Test
  fun convertsCoreLocationMeasurement() {
    val location = sampleMeasurement().copy(ageSeconds = 2.0).asMaplibreLocationMeasurement()

    assertEquals(52.0, location.position.latitude)
    assertEquals(13.0, location.position.longitude)
    assertEquals(40.0, location.position.altitude)
    assertEquals(8.0, location.horizontalAccuracy?.inMeters)
    assertEquals(3.0, location.altitudeAccuracy?.inMeters)
    assertEquals(3.0, location.distancePerSecond?.inMeters)
    assertEquals(0.5, location.distancePerSecondAccuracy?.inMeters)
    assertEquals(Bearing.North + 90.degrees, location.course)
    assertEquals(5.degrees, location.courseAccuracy)
    assertTrue(Clock.System.now() - location.measuredAt >= 2.seconds)

    val invalid =
      sampleMeasurement()
        .copy(verticalAccuracy = -1.0, course = -1.0, speed = -1.0)
        .asMaplibreLocationMeasurement()
    assertNull(invalid.altitudeAccuracy)
    assertNull(invalid.course)
    assertNull(invalid.courseAccuracy)
    assertNull(invalid.distancePerSecond)
    assertNull(invalid.distancePerSecondAccuracy)
  }

  @Test
  fun closeStopsCollectorsAndClosesResourcesOnce() = runTest {
    val client = FakeCoreLocationClient()
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined, Dispatchers.Unconfined)
    val first = launch(start = CoroutineStart.UNDISPATCHED) { provider.updates().collect {} }
    val second = launch(start = CoroutineStart.UNDISPATCHED) { provider.updates().collect {} }
    runCurrent()
    assertEquals(3, client.managers.size)

    provider.close()
    provider.close()
    first.join()
    second.join()

    assertTrue(client.managers.all { it.closeCount == 1 })
    assertEquals(1, client.closeCount)
    assertFailsWith<IllegalStateException> { provider.updates().first() }
    assertFailsWith<IllegalStateException> { provider.requestPermission() }
    assertEquals(3, client.managers.size)
  }

  @Test
  fun reentrantCloseWaitsForPermissionCallToFinish() {
    val client = FakeCoreLocationClient(authorizationStatus = CL_AUTHORIZATION_NOT_DETERMINED)
    val requester = MacosLocationPermissionRequester(client)
    client.managers.single().onRequest = {
      requester.close()
      assertFalse(client.closed)
      assertFalse(client.managers.single().closed)
    }

    requester.requestForegroundPermission()

    assertEquals(1, client.closeCount)
    assertEquals(1, client.managers.single().closeCount)
    assertFalse(client.managers.single().updating)
  }

  @Test
  fun closeFromAnotherThreadWaitsForPermissionCallToFinish() {
    val client = FakeCoreLocationClient(authorizationStatus = CL_AUTHORIZATION_NOT_DETERMINED)
    val requester = MacosLocationPermissionRequester(client)
    val manager = client.managers.single()
    val closing = CountDownLatch(1)
    val closer = Thread {
      closing.countDown()
      requester.close()
    }
    manager.onRequest = {
      closer.start()
      check(closing.await(5, TimeUnit.SECONDS))
      closer.join(200)
      assertTrue(closer.isAlive)
      assertFalse(manager.closed)
      assertFalse(client.closed)
    }

    requester.requestForegroundPermission()
    closer.join(5_000)

    assertFalse(closer.isAlive)
    assertEquals(1, manager.closeCount)
    assertEquals(1, client.closeCount)
  }

  @Test
  fun requestFromStatusCollectorDuringRequestStartsOneAuthorizationRequest() = runTest {
    val client = FakeCoreLocationClient(authorizationStatus = CL_AUTHORIZATION_NOT_DETERMINED)
    client.createFailure = IllegalStateException("native failed")
    val requester = MacosLocationPermissionRequester(client)
    client.createFailure = null
    assertEquals(LocationPermission.Unknown, requester.status.value)
    backgroundScope.launch(Dispatchers.Unconfined) {
      requester.status.collect {
        if (it == LocationPermission.NotGranted(canRequest = true)) {
          requester.requestForegroundPermission()
        }
      }
    }

    requester.requestForegroundPermission()

    assertEquals(1, client.managers.single().whenInUseRequests)
    requester.close()
  }

  @Test
  fun providerForwardsClientUpdatesAndClosesItsManager() = runTest {
    val client = FakeCoreLocationClient()
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)
    val measurement = sampleMeasurement()
    client.nextLocation = measurement

    val event = assertIs<LocationEvent.Update>(provider.updates(LocationRequest()).first())
    assertEquals(52.0, event.measurement.position.latitude)
    assertEquals(2, client.managers.size)
    val manager = client.managers.last()
    assertEquals(CL_LOCATION_ACCURACY_BEST, manager.desiredAccuracy)
    assertEquals(1.0, manager.distanceFilter)
    assertTrue(manager.closed)
    assertFalse(manager.updating)

    provider.close()
    assertTrue(client.managers.all { it.closed })
    assertTrue(client.closed)
  }

  @Test
  fun missingLocationServicesReportsAndCompletes() = runTest {
    val client = FakeCoreLocationClient(locationServicesEnabled = false)
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)
    val managersBeforeUpdates = client.managers.size

    val event =
      assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).toList().single())
    assertEquals(LocationUnavailableReason.ServicesDisabled, event.reason)
    assertEquals(managersBeforeUpdates, client.managers.size)
    provider.close()
  }

  @Test
  fun providerDropsFixesWithInvalidHorizontalAccuracy() = runTest {
    val client = FakeCoreLocationClient()
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined, Dispatchers.Unconfined)
    client.nextLocation = sampleMeasurement().copy(horizontalAccuracy = -1.0)

    val events = mutableListOf<LocationEvent>()
    backgroundScope.launch(Dispatchers.Unconfined) {
      provider.updates(LocationRequest()).collect { events += it }
    }

    assertTrue(events.isEmpty())
    client.managers.last().boundDelegate?.didUpdateLocations(listOf(sampleMeasurement()))
    assertIs<LocationEvent.Update>(events.single())
  }

  @Test
  fun transientErrorKeepsCallbackOrderAheadOfLaterFix() = runTest {
    val client = FakeCoreLocationClient()
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined, Dispatchers.Unconfined)
    client.nextLocation = sampleMeasurement()

    val events = mutableListOf<LocationEvent>()
    backgroundScope.launch(Dispatchers.Unconfined) {
      provider.updates(LocationRequest()).collect { events += it }
    }

    assertIs<LocationEvent.Update>(events.single())
    val delegate = client.managers.last().boundDelegate
    delegate?.didFailWithError(CoreLocationError(CL_ERROR_DOMAIN, CL_ERROR_LOCATION_UNKNOWN))
    delegate?.didUpdateLocations(listOf(sampleMeasurement().copy(latitude = 53.0)))

    assertEquals(3, events.size)
    assertEquals(
      LocationUnavailableReason.TemporarilyUnavailable,
      assertIs<LocationEvent.Unavailable>(events[1]).reason,
    )
    assertEquals(53.0, assertIs<LocationEvent.Update>(events[2]).measurement.position.latitude)
  }

  @Test
  fun deniedErrorRechecksLocationServices() = runTest {
    val client = FakeCoreLocationClient()
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined, Dispatchers.Unconfined)
    client.nextLocation = sampleMeasurement()

    val events = mutableListOf<LocationEvent>()
    backgroundScope.launch(Dispatchers.Unconfined) {
      provider.updates(LocationRequest()).collect { events += it }
    }

    assertIs<LocationEvent.Update>(events.single())
    client.locationServicesEnabled = false
    client.managers
      .last()
      .boundDelegate
      ?.didFailWithError(CoreLocationError(CL_ERROR_DOMAIN, CL_ERROR_DENIED))

    val unavailable = assertIs<LocationEvent.Unavailable>(events.last())
    assertEquals(2, events.size)
    assertEquals(LocationUnavailableReason.ServicesDisabled, unavailable.reason)
  }

  @Test
  fun managerConstructionFailureDoesNotThrowFromProviderConstruction() = runTest {
    val client = FakeCoreLocationClient()
    client.createFailure = IllegalStateException("native failed")

    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)

    assertEquals(LocationPermission.Unknown, provider.permission.value)
    provider.requestPermission()
    val event = assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())
    assertEquals(LocationUnavailableReason.UnexpectedFailure, event.reason)
    assertIs<IllegalStateException>(event.cause)
  }

  @Test
  fun failedPermissionDelegateSetupClosesManagerAndCanRetry() = runTest {
    val failure = IllegalStateException("delegate failed")
    val client = FakeCoreLocationClient().apply { delegateFailure = failure }
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined, Dispatchers.Unconfined)
    assertEquals(LocationPermission.Unknown, provider.permission.value)
    assertEquals(1, client.managers.single().closeCount)

    val event = assertIs<LocationEvent.Unavailable>(provider.updates().first())
    assertEquals(failure, event.cause)
    assertEquals(LocationPermission.Unknown, provider.permission.value)
    assertTrue(client.managers.all { it.closeCount == 1 })

    client.delegateFailure = null
    client.nextLocation = sampleMeasurement()
    assertIs<LocationEvent.Update>(provider.updates().first())
    assertIs<LocationPermission.Granted>(provider.permission.value)
    provider.close()
    assertTrue(client.managers.all { it.closeCount == 1 })
  }

  @Test
  fun collectionRetriesFailedPermissionChecksWithoutPrompting() = runTest {
    val createFailure = IllegalStateException("native failed")
    val client = FakeCoreLocationClient().apply { this.createFailure = createFailure }
    val dispatcher = StandardTestDispatcher(testScheduler)
    val provider = MacosLocationProvider(client, dispatcher, dispatcher)
    val events = mutableListOf<LocationEvent>()
    val collection = backgroundScope.launch { provider.updates().collect(events::add) }
    runCurrent()
    val failed = assertIs<LocationEvent.Unavailable>(events.single())
    assertEquals(LocationUnavailableReason.UnexpectedFailure, failed.reason)
    assertEquals(createFailure, failed.cause)
    assertEquals(LocationPermission.Unknown, provider.permission.value)

    client.createFailure = null
    client.nextLocation = sampleMeasurement()
    advanceTimeBy(1.seconds)
    runCurrent()
    assertIs<LocationEvent.Update>(events.last())
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Precise),
      provider.permission.value,
    )

    val permissionManager = client.managers.first()
    val readFailure = IllegalStateException("permission read failed")
    permissionManager.readFailure = readFailure
    permissionManager.boundDelegate?.didChangeAuthorization()
    runCurrent()
    val unavailable = assertIs<LocationEvent.Unavailable>(events.last())
    assertEquals(LocationUnavailableReason.UnexpectedFailure, unavailable.reason)
    assertEquals(readFailure, unavailable.cause)
    assertEquals(LocationPermission.Unknown, provider.permission.value)
    assertTrue(client.managers.last().closed)

    permissionManager.readFailure = null
    advanceTimeBy(1.seconds)
    runCurrent()
    assertIs<LocationEvent.Update>(events.last())
    assertTrue(client.managers.all { it.whenInUseRequests == 0 })

    permissionManager.readFailure = readFailure
    permissionManager.boundDelegate?.didChangeAuthorization()
    runCurrent()
    collection.cancel()
    runCurrent()
    val readsAfterCancellation = permissionManager.authorizationReads
    advanceTimeBy(2.seconds)
    runCurrent()
    assertEquals(readsAfterCancellation, permissionManager.authorizationReads)
    provider.close()
    assertTrue(client.managers.all { it.closeCount == 1 })
    assertEquals(1, client.closeCount)
  }

  @Test
  fun overlappingPermissionRequestsStartOneAuthorizationRequest() {
    val client = FakeCoreLocationClient(authorizationStatus = CL_AUTHORIZATION_NOT_DETERMINED)
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)
    val manager = client.managers.single()

    assertEquals(LocationPermission.NotGranted(canRequest = true), provider.permission.value)

    provider.requestPermission()
    provider.requestPermission()
    assertEquals(1, manager.whenInUseRequests)
    assertTrue(manager.updating)

    manager.authorizationStatus = CL_AUTHORIZATION_AUTHORIZED_WHEN_IN_USE
    manager.boundDelegate?.didChangeAuthorization()
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Precise),
      provider.permission.value,
    )
    assertFalse(manager.updating)

    provider.requestPermission()
    assertEquals(1, manager.whenInUseRequests)

    provider.close()
    assertTrue(manager.closed)
    assertTrue(client.closed)
  }

  @Test
  fun permissionFailureClearsPendingRequest() {
    val client = FakeCoreLocationClient(authorizationStatus = CL_AUTHORIZATION_NOT_DETERMINED)
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)
    val manager = client.managers.single()

    provider.requestPermission()
    assertEquals(1, manager.whenInUseRequests)
    assertTrue(manager.updating)

    manager.boundDelegate?.didFailWithError(CoreLocationError(CL_ERROR_DOMAIN, CL_ERROR_DENIED))
    assertFalse(manager.updating)

    provider.requestPermission()
    assertEquals(2, manager.whenInUseRequests)
    assertTrue(manager.updating)
  }

  @Test
  fun missingUsageDescriptionMarksProviderMisconfigured() = runTest {
    val client = FakeCoreLocationClient(hasUsageDescription = false)
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)

    assertIs<LocationBackendAvailability.Misconfigured>(provider.backendAvailability)
    assertFailsWith<IllegalStateException> { provider.updates(LocationRequest()).first() }
    provider.requestPermission()
    assertEquals(0, client.managers.single().whenInUseRequests)
  }

  @Test
  fun deniedAuthorizationCannotBeRequestedAgain() {
    val client = FakeCoreLocationClient()
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)
    val manager = client.managers.single()

    manager.authorizationStatus = CL_AUTHORIZATION_DENIED
    manager.boundDelegate?.didChangeAuthorization()
    provider.requestPermission()

    assertEquals(LocationPermission.NotGranted(canRequest = false), provider.permission.value)
    assertEquals(0, manager.whenInUseRequests)
  }
}

private fun sampleMeasurement(): CoreLocationMeasurement =
  CoreLocationMeasurement(
    latitude = 52.0,
    longitude = 13.0,
    altitude = 40.0,
    horizontalAccuracy = 8.0,
    verticalAccuracy = 3.0,
    course = 90.0,
    courseAccuracy = 5.0,
    speed = 3.0,
    speedAccuracy = 0.5,
    ageSeconds = 0.0,
  )

private class FakeCoreLocationClient(
  override var locationServicesEnabled: Boolean = true,
  hasUsageDescription: Boolean = true,
  val authorizationStatus: Long = CL_AUTHORIZATION_AUTHORIZED_WHEN_IN_USE,
) : CoreLocationClient {
  override val backendAvailability: LocationBackendAvailability =
    if (hasUsageDescription) {
      LocationBackendAvailability.Available
    } else {
      LocationBackendAvailability.Misconfigured(IllegalStateException("missing usage description"))
    }
  val managers: MutableList<FakeCoreLocationManager> = Collections.synchronizedList(mutableListOf())
  var closeCount = 0
  var closed = false
  var nextLocation: CoreLocationMeasurement? = null
  var createFailure: Throwable? = null
  var delegateFailure: Throwable? = null
  private val locationThread = Any()

  override fun <T> onLocationThread(action: () -> T): T = synchronized(locationThread, action)

  override fun createManager(): CoreLocationManager {
    createFailure?.let { throw it }
    return FakeCoreLocationManager(nextLocation).also {
      it.authorizationStatus = authorizationStatus
      it.delegateFailure = delegateFailure
      managers += it
    }
  }

  override fun close() {
    closeCount += 1
    closed = true
  }
}

private class FakeCoreLocationManager(override var location: CoreLocationMeasurement? = null) :
  CoreLocationManager {
  override var desiredAccuracy: Double = 0.0
  override var distanceFilter: Double = 0.0
  override var authorizationStatus: Long = CL_AUTHORIZATION_NOT_DETERMINED
    get() {
      authorizationReads += 1
      readFailure?.let { throw it }
      return field
    }

  var authorizationReads = 0
  var readFailure: Throwable? = null
  override var accuracyAuthorization: Long = CL_ACCURACY_AUTHORIZATION_FULL
  var boundDelegate: CoreLocationDelegate? = null
  var updating = false
  var whenInUseRequests = 0
  var onRequest: () -> Unit = {}
  var delegateFailure: Throwable? = null
  var closeCount = 0
  var closed = false

  override fun setDelegate(delegate: CoreLocationDelegate?) {
    delegateFailure?.let { throw it }
    boundDelegate = delegate
  }

  override fun startUpdatingLocation() {
    updating = true
  }

  override fun stopUpdatingLocation() {
    updating = false
  }

  override fun requestWhenInUseAuthorization() {
    whenInUseRequests += 1
    onRequest()
  }

  override fun close() {
    closeCount += 1
    closed = true
    stopUpdatingLocation()
    boundDelegate = null
  }
}
