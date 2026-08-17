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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
  fun convertsCoreLocationFix() {
    val location =
      CoreLocationFix(
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
        .asMapLibreLocation()

    assertEquals(52.0, location.position.value.latitude)
    assertEquals(13.0, location.position.value.longitude)
    assertEquals(40.0, location.position.value.altitude)
    assertEquals(8.0, location.position.accuracy?.inMeters)
    assertEquals(3.0, location.altitudeAccuracy?.inMeters)
    assertEquals(3.0, location.speed?.distancePerSecond?.inMeters)
    assertEquals(0.5, location.speed?.accuracy?.inMeters)
    assertEquals(Bearing.North + 90.degrees, location.course?.value)
    assertEquals(5.degrees, location.course?.accuracy)
    assertTrue(location.timestamp.elapsedNow() >= 2.seconds)
  }

  @Test
  fun omitsInvalidOptionalFixFields() {
    val location =
      CoreLocationFix(
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
        .asMapLibreLocation()

    assertNull(location.altitudeAccuracy)
    assertNull(location.course)
    assertNull(location.speed)
  }

  @Test
  fun providerForwardsClientUpdatesAndClosesItsManager() = runTest {
    val client = FakeCoreLocationClient()
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)
    val fix = sampleFix()
    client.nextLocation = fix

    val event = assertIs<LocationEvent.Fix>(provider.updates(LocationRequest()).first())
    assertEquals(52.0, event.location.position.value.latitude)
    assertEquals(1, client.managers.size)
    assertEquals(CL_LOCATION_ACCURACY_BEST, client.managers.single().desiredAccuracy)
    assertEquals(1.0, client.managers.single().distanceFilter)
    assertTrue(client.managers.single().closed)
    assertFalse(client.managers.single().updating)

    provider.close()
    assertTrue(client.closed)
  }

  @Test
  fun providerAppliesRequestPreferences() = runTest {
    val client = FakeCoreLocationClient()
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)
    client.nextLocation = sampleFix()

    provider
      .updates(LocationRequest(accuracy = LocationAccuracy.Balanced, minimumDistance = 10.meters))
      .first()

    assertEquals(CL_LOCATION_ACCURACY_HUNDRED_METERS, client.managers.single().desiredAccuracy)
    assertEquals(10.0, client.managers.single().distanceFilter)
  }

  @Test
  fun missingLocationServicesEmitsServicesDisabled() = runTest {
    val client = FakeCoreLocationClient(locationServicesEnabled = false)
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)

    val event = assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())
    assertEquals(LocationUnavailableReason.ServicesDisabled, event.reason)
    assertEquals(0, client.managers.size)
  }

  @Test
  fun managerConstructionFailureIsUnexpected() = runTest {
    val client = FakeCoreLocationClient(createFailure = IllegalStateException("native failed"))
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)

    val event = assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())
    assertEquals(LocationUnavailableReason.UnexpectedFailure, event.reason)
    assertIs<IllegalStateException>(event.cause)
  }

  @Test
  fun overlappingPermissionRequestsStartOneAuthorizationRequest() {
    val client = FakeCoreLocationClient()
    val requester = MacosLocationPermissionRequester(client)
    val manager = client.managers.single()

    assertEquals(LocationPermission.NotGranted(canRequest = true), requester.status.value)

    requester.requestForegroundPermission()
    requester.requestForegroundPermission()
    assertEquals(1, manager.whenInUseRequests)
    assertTrue(manager.updating)

    manager.authorizationStatus = CL_AUTHORIZATION_AUTHORIZED_WHEN_IN_USE
    manager.boundDelegate?.didChangeAuthorization()
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Precise),
      requester.status.value,
    )
    assertFalse(manager.updating)

    requester.requestForegroundPermission()
    assertEquals(1, manager.whenInUseRequests)

    requester.close()
    assertTrue(manager.closed)
    assertTrue(client.closed)
  }

  @Test
  fun missingUsageDescriptionMarksProviderAndRequesterMisconfigured() = runTest {
    val client = FakeCoreLocationClient(hasUsageDescription = false)
    val provider = MacosLocationProvider(client, Dispatchers.Unconfined)
    val requester = MacosLocationPermissionRequester(client)

    assertIs<LocationBackendAvailability.Misconfigured>(provider.backendAvailability)
    assertIs<LocationBackendAvailability.Misconfigured>(requester.backendAvailability)
    val event = assertIs<LocationEvent.Unavailable>(provider.updates(LocationRequest()).first())
    assertEquals(LocationUnavailableReason.Misconfigured, event.reason)
    requester.requestForegroundPermission()
    assertEquals(0, client.managers.single().whenInUseRequests)
  }

  @Test
  fun deniedAuthorizationCannotBeRequestedAgain() {
    val client = FakeCoreLocationClient()
    val requester = MacosLocationPermissionRequester(client)
    val manager = client.managers.single()

    manager.authorizationStatus = CL_AUTHORIZATION_DENIED
    manager.boundDelegate?.didChangeAuthorization()
    requester.requestForegroundPermission()

    assertEquals(LocationPermission.NotGranted(canRequest = false), requester.status.value)
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

private fun sampleFix(): CoreLocationFix =
  CoreLocationFix(
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
  private val createFailure: Throwable? = null,
) : CoreLocationClient {
  override val backendAvailability: LocationBackendAvailability =
    if (hasUsageDescription) {
      LocationBackendAvailability.Available
    } else {
      LocationBackendAvailability.Misconfigured(IllegalStateException("missing usage description"))
    }
  val managers = mutableListOf<FakeCoreLocationManager>()
  var closed = false
  var nextLocation: CoreLocationFix? = null

  override fun createManager(): CoreLocationManager {
    createFailure?.let { throw it }
    return FakeCoreLocationManager(nextLocation).also { managers += it }
  }

  override fun close() {
    closed = true
  }
}

private class FakeCoreLocationManager(override var location: CoreLocationFix? = null) :
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
