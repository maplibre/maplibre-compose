package org.maplibre.compose.location.desktop.macos

import java.util.Collections
import java.util.Locale
import java.util.ServiceLoader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

@OptIn(ExperimentalCoroutinesApi::class)
class MacosLocationProviderTest {
  @Test
  fun serviceLoaderFindsMacosBackend() {
    assertTrue(
      ServiceLoader.load(DesktopLocationBackend::class.java).any { it is MacosLocationBackend }
    )
  }

  @Test
  fun isAvailableOnlyOnMac() {
    val onMac = System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("mac")
    assertEquals(onMac, MacosLocationBackend().isAvailable())
  }

  @Test
  fun mapsCoreLocationErrorsByRecoverability() {
    assertEquals(
      LocationUnavailableReason.ServicesDisabled,
      CoreLocationError(CL_ERROR_DOMAIN, CL_ERROR_DENIED).asUnavailableReason(false),
    )
    assertEquals(
      LocationUnavailableReason.PermissionDenied,
      CoreLocationError(CL_ERROR_DOMAIN, CL_ERROR_DENIED).asUnavailableReason(true),
    )
    assertEquals(
      LocationUnavailableReason.PermissionDenied,
      CoreLocationError(CL_ERROR_DOMAIN, CL_ERROR_PROMPT_DECLINED).asUnavailableReason(true),
    )
    assertEquals(
      LocationUnavailableReason.TemporarilyUnavailable,
      CoreLocationError(CL_ERROR_DOMAIN, CL_ERROR_LOCATION_UNKNOWN).asUnavailableReason(true),
    )
    assertEquals(
      LocationUnavailableReason.TemporarilyUnavailable,
      CoreLocationError(CL_ERROR_DOMAIN, CL_ERROR_NETWORK).asUnavailableReason(true),
    )
    assertEquals(
      LocationUnavailableReason.UnexpectedFailure,
      CoreLocationError("example.error", 1).asUnavailableReason(true),
    )
  }

  @Test
  fun mapsAuthorizationStatus() {
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Precise),
      readPermission(CL_AUTHORIZATION_AUTHORIZED_ALWAYS, CL_ACCURACY_AUTHORIZATION_FULL),
    )
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Approximate),
      readPermission(CL_AUTHORIZATION_AUTHORIZED_WHEN_IN_USE, 1),
    )
    assertEquals(
      LocationPermission.NotGranted(canRequest = true),
      readPermission(CL_AUTHORIZATION_NOT_DETERMINED, CL_ACCURACY_AUTHORIZATION_FULL),
    )
    assertEquals(
      LocationPermission.NotGranted(canRequest = false),
      readPermission(CL_AUTHORIZATION_DENIED, CL_ACCURACY_AUTHORIZATION_FULL),
    )
    assertEquals(
      LocationPermission.NotGranted(canRequest = false),
      readPermission(CL_AUTHORIZATION_RESTRICTED, CL_ACCURACY_AUTHORIZATION_FULL),
    )
    assertEquals(
      LocationPermission.NotGranted(canRequest = null),
      readPermission(99, CL_ACCURACY_AUTHORIZATION_FULL),
    )
  }

  @Test
  fun mapsAccuracyPreferences() {
    assertEquals(
      CL_LOCATION_ACCURACY_BEST_FOR_NAVIGATION,
      LocationAccuracy.BestForNavigation.toDesiredAccuracy(),
    )
    assertEquals(CL_LOCATION_ACCURACY_BEST, LocationAccuracy.High.toDesiredAccuracy())
    assertEquals(CL_LOCATION_ACCURACY_HUNDRED_METERS, LocationAccuracy.Balanced.toDesiredAccuracy())
    assertEquals(CL_LOCATION_ACCURACY_KILOMETER, LocationAccuracy.Low.toDesiredAccuracy())
    assertEquals(CL_LOCATION_ACCURACY_REDUCED, LocationAccuracy.Lowest.toDesiredAccuracy())
    assertTrue(CL_LOCATION_ACCURACY_REDUCED != 500.0)
    ObjectiveC.exportedDoubleOrNull("kCLLocationAccuracyReduced")?.let { exported ->
      assertEquals(exported, CL_LOCATION_ACCURACY_REDUCED)
    }
  }

  @Test
  fun convertsCoreLocationMeasurement() {
    val location =
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
          ageSeconds = 2.0,
        )
        .asMapLibreLocationMeasurement()

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
  }

  @Test
  fun omitsInvalidOptionalFixFields() {
    val location =
      CoreLocationMeasurement(
          latitude = 1.0,
          longitude = 2.0,
          altitude = 0.0,
          horizontalAccuracy = 4.0,
          verticalAccuracy = -1.0,
          course = -1.0,
          courseAccuracy = -1.0,
          speed = -1.0,
          speedAccuracy = -1.0,
          ageSeconds = 0.0,
        )
        .asMapLibreLocationMeasurement()

    assertNull(location.altitudeAccuracy)
    assertNull(location.course)
    assertNull(location.distancePerSecond)
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
  fun standaloneRequesterOwnsClientAndClosesOnce() {
    val client = FakeCoreLocationClient()
    val requester = MacosLocationPermissionRequester(client)
    requester.close()
    requester.close()
    assertEquals(1, client.closeCount)
    assertEquals(1, client.managers.single().closeCount)
    assertFailsWith<IllegalStateException> { requester.requestForegroundPermission() }
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
  fun providerAppliesRequestPreferences() = runTest {
    val client = FakeCoreLocationClient()
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)
    client.nextLocation = sampleMeasurement()

    provider
      .updates(LocationRequest(accuracy = LocationAccuracy.Balanced, minimumDistance = 10.meters))
      .first()

    assertEquals(CL_LOCATION_ACCURACY_HUNDRED_METERS, client.managers.last().desiredAccuracy)
    assertEquals(10.0, client.managers.last().distanceFilter)
  }

  @Test
  fun missingLocationServicesEmitsServicesDisabled() = runTest {
    val client = FakeCoreLocationClient(locationServicesEnabled = false)
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)
    val managersBeforeUpdates = client.managers.size

    val event = assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())
    assertEquals(LocationUnavailableReason.ServicesDisabled, event.reason)
    assertEquals(managersBeforeUpdates, client.managers.size)
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
  fun managerConstructionFailureIsUnexpected() = runTest {
    val client = FakeCoreLocationClient()
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)
    client.createFailure = IllegalStateException("native failed")

    val event = assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())
    assertEquals(LocationUnavailableReason.UnexpectedFailure, event.reason)
    assertIs<IllegalStateException>(event.cause)
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
  fun collectionRetriesPermissionInitializationWithoutPrompting() = runTest {
    val failure = IllegalStateException("native failed")
    val client = FakeCoreLocationClient().apply { createFailure = failure }
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined, Dispatchers.Unconfined)

    val failed = assertIs<LocationEvent.Unavailable>(provider.updates().first())
    assertEquals(LocationUnavailableReason.UnexpectedFailure, failed.reason)
    assertEquals(failure, failed.cause)
    assertEquals(LocationPermission.Unknown, provider.permission.value)

    client.createFailure = null
    client.nextLocation = sampleMeasurement()
    assertIs<LocationEvent.Update>(provider.updates().first())
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Precise),
      provider.permission.value,
    )
    assertEquals(2, client.managers.size)
    assertTrue(client.managers.all { it.whenInUseRequests == 0 })
    provider.close()
    assertTrue(client.managers.all { it.closeCount == 1 })
    assertEquals(1, client.closeCount)
  }

  @Test
  fun initializationRetryDoesNotStartUpdatesWithoutAuthorization() = runTest {
    for (authorization in listOf(CL_AUTHORIZATION_DENIED, CL_AUTHORIZATION_NOT_DETERMINED)) {
      val client = FakeCoreLocationClient(authorizationStatus = authorization)
      client.createFailure = IllegalStateException("native failed")
      val provider = MacosLocationProvider(client, Dispatchers.Unconfined, Dispatchers.Unconfined)
      client.createFailure = null

      val event = assertIs<LocationEvent.Unavailable>(provider.updates().first())

      assertEquals(LocationUnavailableReason.PermissionDenied, event.reason)
      assertEquals(
        LocationPermission.NotGranted(
          canRequest = authorization == CL_AUTHORIZATION_NOT_DETERMINED
        ),
        provider.permission.value,
      )
      val manager = client.managers.single()
      assertEquals(0, manager.whenInUseRequests)
      assertEquals(0, manager.startCount)
      provider.close()
      assertEquals(1, manager.closeCount)
    }
  }

  @Test
  fun closeDuringInitializationRetryDoesNotStartDeliveryOrLeakManager() = runTest {
    val client =
      FakeCoreLocationClient().apply { createFailure = IllegalStateException("native failed") }
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined, Dispatchers.Unconfined)
    client.createFailure = null
    client.onCreate = { provider.close() }

    assertTrue(provider.updates().toList().isEmpty())

    val manager = client.managers.single()
    assertEquals(0, manager.whenInUseRequests)
    assertEquals(0, manager.startCount)
    assertEquals(1, manager.closeCount)
    assertEquals(1, client.closeCount)
  }

  @Test
  fun cancellingCollectorDuringPermissionRetryDoesNotStartDelivery() = runTest {
    val client =
      FakeCoreLocationClient().apply { createFailure = IllegalStateException("native failed") }
    val provider = MacosLocationProvider(client, Dispatchers.IO, Dispatchers.IO)
    client.createFailure = null
    val creating = CountDownLatch(1)
    val resume = CountDownLatch(1)
    client.onCreate = {
      creating.countDown()
      check(resume.await(5, TimeUnit.SECONDS))
    }
    val collection = launch { provider.updates().collect {} }
    try {
      withContext(Dispatchers.IO) { check(creating.await(5, TimeUnit.SECONDS)) }
      collection.cancel()
      runCurrent()
    } finally {
      resume.countDown()
    }
    collection.join()

    val manager = client.managers.single()
    assertEquals(0, manager.startCount)
    assertEquals(0, manager.whenInUseRequests)
    assertEquals(0, client.closeCount)
    provider.close()
    assertEquals(1, manager.closeCount)
    assertEquals(1, client.closeCount)
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
    assertTrue(client.managers.all { it.closeCount == 1 })
    client.delegateFailure = null
    client.nextLocation = sampleMeasurement()
    assertIs<LocationEvent.Update>(provider.updates().first())
    provider.close()
    assertTrue(client.managers.all { it.closeCount == 1 })
  }

  @Test
  fun concurrentInitializationRetriesKeepOnePermissionManager() {
    val client =
      FakeCoreLocationClient().apply { createFailure = IllegalStateException("native failed") }
    val requester = MacosLocationPermissionRequester(client)
    client.createFailure = null
    val creating = CountDownLatch(2)
    client.onCreate = {
      creating.countDown()
      check(creating.await(5, TimeUnit.SECONDS))
    }

    Executors.newFixedThreadPool(2).use { executor ->
      val attempts =
        (1..2).map { executor.submit<LocationPermission> { requester.refreshPermission() } }
      attempts.forEach {
        assertIs<LocationPermission.Granted>(it.get(5, TimeUnit.SECONDS))
      }
    }

    assertEquals(2, client.managers.size)
    assertEquals(1, client.managers.count { it.closed })
    requester.close()
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

  @Test
  fun cocoaMainRunsInlineWhenAlreadyOnMain() {
    var dispatched = false
    val result =
      CocoaMain.run(alreadyOnMain = true, dispatch = { dispatched = true }) {
        42
      }
    assertEquals(42, result)
    assertFalse(dispatched)
  }

  @Test
  fun cocoaMainDispatchesWhenOffMainAndPropagatesResult() {
    var dispatched = false
    val result =
      CocoaMain.run(
        alreadyOnMain = false,
        dispatch = { work ->
          dispatched = true
          work.run()
        },
      ) {
        7
      }
    assertTrue(dispatched)
    assertEquals(7, result)
  }

  @Test
  fun cocoaMainPropagatesFailureFromDispatchedWork() {
    val error =
      assertFailsWith<IllegalStateException> {
        CocoaMain.run(alreadyOnMain = false, dispatch = { it.run() }) { error("boom") }
      }
    assertEquals("boom", error.message)
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
  var onCreate: () -> Unit = {}

  override fun createManager(): CoreLocationManager {
    createFailure?.let { throw it }
    return FakeCoreLocationManager(nextLocation).also {
      it.authorizationStatus = authorizationStatus
      it.delegateFailure = delegateFailure
      managers += it
      onCreate()
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
  override var accuracyAuthorization: Long = CL_ACCURACY_AUTHORIZATION_FULL
  var boundDelegate: CoreLocationDelegate? = null
  var updating = false
  var whenInUseRequests = 0
  var startCount = 0
  var delegateFailure: Throwable? = null
  var onRequest: () -> Unit = {}
  var closeCount = 0
  var closed = false

  override fun setDelegate(delegate: CoreLocationDelegate?) {
    delegateFailure?.let { throw it }
    boundDelegate = delegate
  }

  override fun startUpdatingLocation() {
    startCount += 1
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
