package org.maplibre.compose.location.desktop.macos

import java.util.Locale
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.location.DesktopLocationBackend
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationAccuracyAuthorization
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationProviderAvailability
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationServicesStatus
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.inMeters
import org.maplibre.spatialk.units.extensions.meters

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
      LocationPermission.Required(canRequest = true),
      readPermission(CL_AUTHORIZATION_NOT_DETERMINED, CL_ACCURACY_AUTHORIZATION_FULL),
    )
    assertEquals(
      LocationPermission.Required(canRequest = false),
      readPermission(CL_AUTHORIZATION_DENIED, CL_ACCURACY_AUTHORIZATION_FULL),
    )
    assertEquals(
      LocationPermission.Required(canRequest = false),
      readPermission(CL_AUTHORIZATION_RESTRICTED, CL_ACCURACY_AUTHORIZATION_FULL),
    )
    assertEquals(
      LocationPermission.Unknown,
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
    assertEquals(3.0, location.speed?.inMeters)
    assertEquals(0.5, location.speedAccuracy?.inMeters)
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
    assertNull(location.speed)
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
    assertEquals(LocationServicesStatus.Disabled, provider.locationServices.value)
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
    assertEquals(LocationServicesStatus.Disabled, provider.locationServices.value)
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
  fun overlappingPermissionRequestsStartOneAuthorizationRequest() {
    val client = FakeCoreLocationClient()
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)
    val manager = client.managers.single()

    assertEquals(LocationPermission.Required(canRequest = true), provider.permission.value)

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
    val client = FakeCoreLocationClient()
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

    assertIs<LocationProviderAvailability.Misconfigured>(provider.availability)
    val event = assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())
    assertEquals(LocationUnavailableReason.Misconfigured, event.reason)
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

    assertEquals(LocationPermission.Required(canRequest = false), provider.permission.value)
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
) : CoreLocationClient {
  override val backendAvailability: LocationProviderAvailability =
    if (hasUsageDescription) {
      LocationProviderAvailability.Available
    } else {
      LocationProviderAvailability.Misconfigured(IllegalStateException("missing usage description"))
    }
  val managers = mutableListOf<FakeCoreLocationManager>()
  var closed = false
  var nextLocation: CoreLocationMeasurement? = null
  var createFailure: Throwable? = null

  override fun createManager(): CoreLocationManager {
    createFailure?.let { throw it }
    return FakeCoreLocationManager(nextLocation).also { managers += it }
  }

  override fun close() {
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
  var closed = false

  override fun setDelegate(delegate: CoreLocationDelegate?) {
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
  }

  override fun close() {
    closed = true
    stopUpdatingLocation()
    boundDelegate = null
  }
}
