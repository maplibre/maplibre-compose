package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
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
  fun watchMapsCoordinatesThrottlesUpdatesAndStopsOnCancellation() = runTest {
    val boundary = FakeBrowserGeolocationBoundary()
    val provider = BrowserLocationProvider(boundary)
    val events = mutableListOf<LocationEvent>()
    val request = LocationRequest(minimumInterval = 1.seconds)

    runCurrent()
    val collection = backgroundScope.launch { provider.updates(request).collect(events::add) }
    runCurrent()
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
    val provider = BrowserLocationProvider(boundary)
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
  fun permissionRequesterObservesAndExplicitlyRequestsPermission() = runTest {
    val boundary = FakeBrowserGeolocationBoundary()
    boundary.permission.value = BrowserPermission.Prompt
    val requester = BrowserLocationPermissionRequester(boundary, backgroundScope)
    runCurrent()
    assertEquals(LocationPermission.NotGranted(canRequest = true), requester.status.value)

    boundary.permission.value = BrowserPermission.Granted
    runCurrent()
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Unknown),
      requester.status.value,
    )

    boundary.permission.value = BrowserPermission.Prompt
    boundary.requestPositionAction = { BrowserResult.Error(BrowserError.PermissionDenied) }
    runCurrent()
    requester.requestForegroundPermission()
    runCurrent()
    assertEquals(LocationPermission.NotGranted(canRequest = false), requester.status.value)
    assertEquals(1.seconds, boundary.requestedOptions.single().timeout)
  }

  @Test
  fun nonDenialRequestErrorConfirmsPermissionWhenQueryIsUnavailable() = runTest {
    val boundary = FakeBrowserGeolocationBoundary()
    boundary.permission.value = BrowserPermission.Unknown
    boundary.requestPositionAction = { BrowserResult.Error(BrowserError.PositionUnavailable) }
    val requester = BrowserLocationPermissionRequester(boundary, backgroundScope)
    runCurrent()

    assertEquals(LocationPermission.NotGranted(canRequest = null), requester.status.value)
    requester.requestForegroundPermission()
    runCurrent()
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Unknown),
      requester.status.value,
    )
  }

  @Test
  fun overlappingPermissionRequestsStartOneBrowserRequest() = runTest {
    val boundary = FakeBrowserGeolocationBoundary()
    boundary.permission.value = BrowserPermission.Prompt
    val result = CompletableDeferred<BrowserResult>()
    boundary.requestPositionAction = { result.await() }
    val requester = BrowserLocationPermissionRequester(boundary, backgroundScope)
    runCurrent()

    requester.requestForegroundPermission()
    requester.requestForegroundPermission()
    runCurrent()

    assertEquals(1, boundary.requestedOptions.size)
    result.complete(position(milliseconds = 0, longitude = 0.0))
    runCurrent()
    assertEquals(
      LocationPermission.Granted(LocationAccuracyAuthorization.Unknown),
      requester.status.value,
    )
  }

  @Test
  fun cachedInitialLookupIsSeparateFromTheLiveWatch() = runTest {
    val boundary = FakeBrowserGeolocationBoundary()
    val cachedResult = CompletableDeferred<BrowserResult>()
    boundary.requestPositionAction = { cachedResult.await() }
    val provider = BrowserLocationProvider(boundary)
    val events = mutableListOf<LocationEvent>()
    runCurrent()

    backgroundScope.launch { provider.updates(LocationRequest()).collect(events::add) }
    runCurrent()
    assertEquals(30.seconds, boundary.requestedOptions.single().maximumAge)
    assertEquals(kotlin.time.Duration.ZERO, boundary.watchedOptions.single().maximumAge)

    boundary.send(position(milliseconds = 2_000, longitude = 0.001))
    cachedResult.complete(position(milliseconds = 0, longitude = 0.0))
    runCurrent()

    assertEquals(1, events.size)
    assertEquals(
      0.001,
      assertIs<LocationEvent.Fix>(events.single()).location.position.value.longitude,
    )
  }

  @Test
  fun browserOptionsPreserveLargeAndUnboundedCachedFixAges() {
    val unbounded: dynamic =
      BrowserOptions(highAccuracy = false, maximumAge = null).toPositionOptions()
    val fortyDays: dynamic =
      BrowserOptions(highAccuracy = false, maximumAge = 40.days).toPositionOptions()

    assertEquals(Double.POSITIVE_INFINITY, unbounded.maximumAge)
    assertEquals(3_456_000_000.0, fortyDays.maximumAge)
  }

  private fun position(
    milliseconds: Long,
    longitude: Double,
    altitude: Double? = null,
  ): BrowserResult.Position =
    BrowserResult.Position(
      BrowserPosition(
        longitude = longitude,
        latitude = 0.0,
        altitude = altitude,
        horizontalAccuracyMeters = 4.0,
        altitudeAccuracyMeters = 2.0,
        speedMetersPerSecond = 3.0,
        headingDegrees = 45.0,
        capturedAt = Instant.fromEpochMilliseconds(1_700_000_000_000 + milliseconds),
      )
    )
}

private class FakeBrowserGeolocationBoundary : BrowserGeolocationBoundary {
  override val supported = true
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
