package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.inMeters

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserLocationProviderTest {
  @Test
  fun missingGeolocationMarksProviderUnsupported() = runTest {
    val boundary = FakeBrowserGeolocationBoundary(supported = false)
    val provider = BrowserLocationProvider(boundary, backgroundScope)

    assertEquals(LocationBackendAvailability.Unsupported, provider.backendAvailability)
    provider.requestPermission()
    runCurrent()
    assertEquals(emptyList(), boundary.requestedOptions)
  }

  @Test
  fun watchMapsCoordinatesThrottlesUpdatesAndStopsOnCancellation() = runTest {
    val boundary = FakeBrowserGeolocationBoundary()
    val provider = BrowserLocationProvider(boundary, backgroundScope)
    val events = mutableListOf<LocationEvent>()
    val request = LocationRequest(minimumInterval = 1.seconds)

    runCurrent()
    val collection = backgroundScope.launch { provider.updates(request).collect(events::add) }
    runCurrent()
    assertEquals(emptyList(), boundary.requestedOptions)
    assertEquals(1, boundary.watchedOptions.size)
    boundary.send(position(milliseconds = 0, longitude = 0.0, altitude = 12.0))
    boundary.send(position(milliseconds = 500, longitude = 0.001))
    boundary.send(position(milliseconds = 2_000, longitude = 0.000001))
    boundary.send(position(milliseconds = 2_500, longitude = 0.001))
    runCurrent()

    assertEquals(2, events.size)
    val first = assertIs<LocationEvent.Fix>(events[0]).location
    assertEquals(12.0, first.position.value.altitude)
    assertEquals(4.0, first.position.accuracy?.inMeters)
    assertEquals(2.0, first.altitudeAccuracy?.inMeters)
    assertEquals(3.0, first.speed?.distancePerSecond?.inMeters)
    assertEquals(Bearing.North + 45.degrees, first.course?.value)

    collection.cancel()
    runCurrent()
    assertEquals(1, boundary.stopCount)
    assertNull(boundary.callback)
  }

  @Test
  fun browserErrorsBecomeTypedEvents() = runTest {
    val boundary = FakeBrowserGeolocationBoundary()
    val provider = BrowserLocationProvider(boundary, backgroundScope)
    val events = mutableListOf<LocationEvent>()
    runCurrent()
    backgroundScope.launch { provider.updates(LocationRequest()).collect(events::add) }
    runCurrent()

    boundary.send(BrowserResult.Error(BrowserError.Timeout))
    boundary.send(BrowserResult.Error(BrowserError.Unknown))
    boundary.send(BrowserResult.Error(BrowserError.PermissionDenied))
    runCurrent()

    assertEquals(
      listOf(
        LocationUnavailableReason.TemporarilyUnavailable,
        LocationUnavailableReason.UnexpectedFailure,
        LocationUnavailableReason.PermissionDenied,
      ),
      events.map { assertIs<LocationEvent.Unavailable>(it).reason },
    )
    assertEquals(1, boundary.stopCount)
  }

  @Test
  fun watchPermissionDenialUpdatesPermissionState() = runTest {
    val boundary = FakeBrowserGeolocationBoundary()
    val provider = BrowserLocationProvider(boundary, backgroundScope)
    backgroundScope.launch { provider.updates(LocationRequest()).collect {} }
    runCurrent()

    boundary.send(BrowserResult.Error(BrowserError.PermissionDenied))
    runCurrent()

    assertEquals(LocationPermission.NotGranted(canRequest = null), provider.permission.value)
    provider.requestPermission()
    runCurrent()
    assertEquals(1, boundary.requestedOptions.size)
    assertEquals(1, boundary.stopCount)
  }

  @Test
  fun nonFiniteHeadingsAreNotPublishedAsCourse() = runTest {
    val boundary = FakeBrowserGeolocationBoundary()
    val provider = BrowserLocationProvider(boundary, backgroundScope)
    val events = mutableListOf<LocationEvent>()
    backgroundScope.launch { provider.updates(LocationRequest()).collect(events::add) }
    runCurrent()

    boundary.send(position(milliseconds = 0, longitude = 0.0, heading = Double.NaN))
    boundary.send(
      position(milliseconds = 2_000, longitude = 0.0, heading = Double.POSITIVE_INFINITY)
    )
    runCurrent()

    assertEquals(2, events.size)
    events.forEach { assertNull(assertIs<LocationEvent.Fix>(it).location.course) }
  }

  @Test
  fun firstFixAfterTransientErrorBypassesUpdateThrottle() = runTest {
    val boundary = FakeBrowserGeolocationBoundary()
    val provider = BrowserLocationProvider(boundary, backgroundScope)
    val events = mutableListOf<LocationEvent>()
    backgroundScope.launch {
      provider.updates(LocationRequest(minimumInterval = 10.seconds)).collect(events::add)
    }
    runCurrent()

    boundary.send(position(milliseconds = 0, longitude = 1.0))
    boundary.send(BrowserResult.Error(BrowserError.Timeout))
    boundary.send(position(milliseconds = 1_000, longitude = 2.0))
    runCurrent()

    assertEquals(3, events.size)
    assertEquals(1.0, assertIs<LocationEvent.Fix>(events[0]).location.position.value.longitude)
    assertIs<LocationEvent.Unavailable>(events[1])
    assertEquals(2.0, assertIs<LocationEvent.Fix>(events[2]).location.position.value.longitude)
  }

  @Test
  fun providerObservesAndExplicitlyRequestsPermission() = runTest {
    val boundary = FakeBrowserGeolocationBoundary()
    boundary.permission.value = BrowserPermission.Prompt
    val provider = BrowserLocationProvider(boundary, backgroundScope)
    runCurrent()
    assertEquals(LocationPermission.NotGranted(canRequest = true), provider.permission.value)

    boundary.permission.value = BrowserPermission.Granted
    runCurrent()
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Unknown),
      provider.permission.value,
    )

    boundary.permission.value = BrowserPermission.Prompt
    boundary.requestPositionAction = { BrowserResult.Error(BrowserError.PermissionDenied) }
    runCurrent()
    provider.requestPermission()
    runCurrent()
    assertEquals(LocationPermission.NotGranted(canRequest = true), provider.permission.value)
    provider.requestPermission()
    runCurrent()
    assertEquals(2, boundary.requestedOptions.size)
    assertEquals(listOf(1.seconds, 1.seconds), boundary.requestedOptions.map { it.timeout })
  }

  @Test
  fun nonDenialRequestErrorConfirmsPermissionWhenQueryIsUnavailable() = runTest {
    val boundary = FakeBrowserGeolocationBoundary()
    boundary.permission.value = BrowserPermission.Unknown
    boundary.requestPositionAction = { BrowserResult.Error(BrowserError.PositionUnavailable) }
    val provider = BrowserLocationProvider(boundary, backgroundScope)
    runCurrent()

    assertEquals(LocationPermission.NotGranted(canRequest = null), provider.permission.value)
    provider.requestPermission()
    runCurrent()
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Unknown),
      provider.permission.value,
    )
  }

  @Test
  fun overlappingPermissionRequestsStartOneBrowserRequest() = runTest {
    val boundary = FakeBrowserGeolocationBoundary()
    boundary.permission.value = BrowserPermission.Prompt
    val result = CompletableDeferred<BrowserResult>()
    boundary.requestPositionAction = { result.await() }
    val provider = BrowserLocationProvider(boundary, backgroundScope)
    runCurrent()

    provider.requestPermission()
    provider.requestPermission()
    runCurrent()

    assertEquals(1, boundary.requestedOptions.size)
    result.complete(position(milliseconds = 0, longitude = 0.0))
    runCurrent()
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Unknown),
      provider.permission.value,
    )
  }

  @Test
  fun failedPermissionRequestCanBeRetried() = runTest {
    val boundary = FakeBrowserGeolocationBoundary()
    boundary.permission.value = BrowserPermission.Prompt
    var fail = true
    boundary.requestPositionAction = {
      if (fail) error("geolocation failed")
      position(milliseconds = 0, longitude = 0.0)
    }
    val provider = BrowserLocationProvider(boundary, backgroundScope)
    runCurrent()

    provider.requestPermission()
    runCurrent()
    assertEquals(LocationPermission.NotGranted(canRequest = null), provider.permission.value)

    fail = false
    provider.requestPermission()
    runCurrent()
    assertEquals(2, boundary.requestedOptions.size)
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Unknown),
      provider.permission.value,
    )
  }

  private fun position(
    milliseconds: Long,
    longitude: Double,
    altitude: Double? = null,
    heading: Double? = 45.0,
  ): BrowserResult.Position =
    BrowserResult.Position(
      BrowserPosition(
        longitude = longitude,
        latitude = 0.0,
        altitude = altitude,
        horizontalAccuracyMeters = 4.0,
        altitudeAccuracyMeters = 2.0,
        speedMetersPerSecond = 3.0,
        headingDegrees = heading,
        capturedAt = Instant.fromEpochMilliseconds(1_700_000_000_000 + milliseconds),
      )
    )
}

private class FakeBrowserGeolocationBoundary(override val supported: Boolean = true) :
  BrowserGeolocationBoundary {
  override val permissionState = BrowserLocationPermissionState()
  val permission = MutableStateFlow(BrowserPermission.Granted)
  var requestPositionAction: suspend (BrowserOptions) -> BrowserResult = { awaitCancellation() }
  val requestedOptions = mutableListOf<BrowserOptions>()
  val watchedOptions = mutableListOf<BrowserOptions>()
  var callback: ((BrowserResult) -> Unit)? = null
  var stopCount = 0

  override fun permissionChanges(): Flow<BrowserPermission> = permission

  override suspend fun requestPosition(options: BrowserOptions): BrowserResult {
    requestedOptions += options
    return requestPositionAction(options)
  }

  override fun startWatch(
    options: BrowserOptions,
    onResult: (BrowserResult) -> Unit,
  ): () -> Unit {
    watchedOptions += options
    callback = onResult
    return {
      stopCount += 1
      callback = null
    }
  }

  fun send(result: BrowserResult) {
    callback?.invoke(result)
  }
}
