package org.maplibre.compose.location

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class LocationStateTest {
  @Test
  fun providerMisconfigurationPreventsCollection() = withMainDispatcher {
    runComposeUiTest {
      val cause = IllegalStateException("multiple backends")
      val provider =
        ActiveLocationProvider(
          location(13.0),
          backendAvailability = LocationBackendAvailability.Misconfigured(cause),
        )
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = provider,
            lifecycleOwner = ResumedLifecycleOwner(),
          )
      }

      waitUntil { state?.availability == LocationBackendAvailability.Misconfigured(cause) }
      assertEquals(LocationTrackingStatus.Stopped, state?.status)
      assertFalse(provider.active)
    }
  }

  @Test
  fun unknownPermissionRetriesFailureWithoutRestartingOnGrant() = withMainDispatcher {
    runComposeUiTest {
      val failure = IllegalStateException("permission initialization failed")
      val permissionState = MutableStateFlow<LocationPermission>(LocationPermission.NotDetermined)
      val locationProvider = ActiveLocationProvider(location(13.0))
      var attempts = 0
      var permissionRequests = 0
      val provider =
        object : LocationProvider {
          override val permission = permissionState

          override fun requestPermission() {
            permissionRequests++
          }

          override fun updates(request: LocationRequest): Flow<LocationEvent> = flow {
            attempts++
            if (attempts == 1) {
              emit(LocationEvent.Unavailable(null, failure))
            } else {
              permission.value = LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
              emitAll(locationProvider.updates(request))
            }
          }
        }
      val lifecycleOwner = ResumedLifecycleOwner()
      var state: LocationState? = null
      setContent {
        state = rememberLocationState(provider = provider, lifecycleOwner = lifecycleOwner)
      }

      waitUntil { state?.status is LocationTrackingStatus.Unavailable }
      assertEquals(LocationPermission.NotDetermined, state?.permission)
      assertSame(failure, assertIs<LocationTrackingStatus.Unavailable>(state?.status).cause)
      runOnIdle { state?.retry() }
      waitUntil { state?.lastLocation == locationProvider.location }
      waitForIdle()
      assertEquals(2, attempts)
      assertEquals(0, locationProvider.stopCount)
      assertEquals(0, permissionRequests)

      runOnIdle { permissionState.value = LocationPermission.NotGranted(canRequest = false) }
      waitUntil { !locationProvider.active }
      assertEquals(LocationTrackingStatus.Stopped, state?.status)
    }
  }

  @Test
  fun unknownPermissionStopsWhenAuthorizationIsDeniedWithoutRequestingIt() = withMainDispatcher {
    runComposeUiTest {
      val permissionState = MutableStateFlow<LocationPermission>(LocationPermission.NotDetermined)
      var attempts = 0
      var permissionRequests = 0
      var stopped = false
      val provider =
        object : LocationProvider {
          override val permission = permissionState

          override fun requestPermission() {
            permissionRequests++
          }

          override fun updates(request: LocationRequest): Flow<LocationEvent> = flow {
            attempts++
            permission.value = LocationPermission.NotGranted(canRequest = true)
            try {
              awaitCancellation()
            } finally {
              stopped = true
            }
          }
        }
      val lifecycleOwner = ResumedLifecycleOwner()
      var state: LocationState? = null
      setContent {
        state = rememberLocationState(provider = provider, lifecycleOwner = lifecycleOwner)
      }
      waitUntil { stopped && state?.permission is LocationPermission.NotGranted }
      assertEquals(LocationTrackingStatus.Stopped, state?.status)
      assertEquals(1, attempts)
      assertEquals(0, permissionRequests)
    }
  }

  @Test
  fun permissionGrantStartsAndRevocationStopsProviderUpdates() = withMainDispatcher {
    runComposeUiTest {
      val lifecycleOwner = ResumedLifecycleOwner()
      val locationProvider = ActiveLocationProvider(location(13.0))
      val provider = MutablePermissionProvider(locationProvider)
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = provider,
            lifecycleOwner = lifecycleOwner,
          )
      }

      waitUntil {
        state?.permission == LocationPermission.NotGranted(canRequest = true) &&
          state.status == LocationTrackingStatus.Stopped
      }
      assertFalse(locationProvider.active)
      runOnIdle { state?.requestPermission() }
      assertEquals(1, provider.requestCount)

      runOnIdle {
        provider.permission.value =
          LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
      }
      waitUntil { locationProvider.active && state?.lastLocation == locationProvider.location }

      runOnIdle {
        provider.permission.value = LocationPermission.NotGranted(canRequest = false)
      }
      waitUntil { !locationProvider.active && state?.status == LocationTrackingStatus.Stopped }
      assertTrue(locationProvider.stopCount > 0)
    }
  }

  @Test
  fun headingCollectsWithoutLocationPermission() = withMainDispatcher {
    runComposeUiTest {
      val headingProvider = MutableHeadingProvider()
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = MutablePermissionProvider(FiniteLocationProvider()),
            headingProvider = headingProvider,
            lifecycleOwner = ResumedLifecycleOwner(),
          )
      }

      waitUntil { headingProvider.activeCollectors == 1 }
      assertEquals(LocationPermission.NotGranted(canRequest = true), state?.permission)
    }
  }

  @Test
  fun lifecycleStartsAndStopsHeadingUpdates() = withMainDispatcher {
    runComposeUiTest {
      val lifecycleOwner = ResumedLifecycleOwner()
      val headingProvider = MutableHeadingProvider()
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = FiniteLocationProvider(),
            headingProvider = headingProvider,
            lifecycleOwner = lifecycleOwner,
          )
      }

      waitUntil { headingProvider.activeCollectors == 1 }
      runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.CREATED) }
      waitUntil {
        headingProvider.activeCollectors == 0 &&
          state?.headingStatus == HeadingTrackingStatus.Stopped
      }
    }
  }

  @Test
  fun defaultPermissionProviderCollectsWithoutPermissionWiring() = withMainDispatcher {
    runComposeUiTest {
      val provider = ActiveLocationProvider(location(13.0))
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = provider,
            lifecycleOwner = ResumedLifecycleOwner(),
          )
      }

      waitUntil { provider.active && state?.lastLocation == provider.location }
      assertEquals(
        LocationPermission.Granted(accuracy = null),
        state?.permission,
      )
    }
  }

  @Test
  fun headingFailureDoesNotStopLocationCollection() = withMainDispatcher {
    runComposeUiTest {
      val locationProvider = ActiveLocationProvider(location(13.0))
      val failure = IllegalStateException("sensor failed")
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = locationProvider,
            headingProvider = FailingHeadingProvider(failure),
            lifecycleOwner = ResumedLifecycleOwner(),
          )
      }

      waitUntil {
        locationProvider.active &&
          state?.let {
            it.lastLocation == locationProvider.location &&
              it.headingStatus == HeadingTrackingStatus.Unavailable(reason = null, cause = failure)
          } == true
      }
      assertEquals(LocationTrackingStatus.Tracking, state?.status)
    }
  }

  @Test
  fun unavailableHeadingClearsLastHeadingUntilRetry() = withMainDispatcher {
    runComposeUiTest {
      val expected = heading(90.0)
      val headingProvider = RetryableHeadingProvider(expected)
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = ActiveLocationProvider(location(13.0)),
            headingProvider = headingProvider,
            lifecycleOwner = ResumedLifecycleOwner(),
          )
      }

      waitUntil {
        state?.headingStatus == HeadingTrackingStatus.Unavailable(reason = null) &&
          state.lastHeading == null
      }
      runOnIdle { state?.retry() }
      waitUntil {
        headingProvider.attempts == 2 &&
          state?.lastHeading == expected &&
          state.headingStatus == HeadingTrackingStatus.Tracking
      }
    }
  }

  @Test
  fun retryRestartsLocationAfterUnavailableSession() = withMainDispatcher {
    runComposeUiTest {
      val expected = location(13.0)
      val provider = RetryableLocationProvider(expected)
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = provider,
            lifecycleOwner = ResumedLifecycleOwner(),
          )
      }

      waitUntil {
        state?.status ==
          LocationTrackingStatus.Unavailable(LocationUnavailableReason.ServicesDisabled)
      }
      runOnIdle { state?.retry() }
      waitUntil {
        provider.attempts == 2 &&
          state?.lastLocation == expected &&
          state.status == LocationTrackingStatus.Tracking
      }
    }
  }

  @Test
  fun disablingStopsCollectionsAndRetainsLastValues() = withMainDispatcher {
    runComposeUiTest {
      val expectedLocation = location(13.0)
      val expectedHeading = heading(90.0)
      val locationProvider = ActiveLocationProvider(expectedLocation)
      val headingProvider = MutableHeadingProvider()
      var enabled by mutableStateOf(true)
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            enabled = enabled,
            provider = locationProvider,
            headingProvider = headingProvider,
            lifecycleOwner = ResumedLifecycleOwner(),
          )
      }

      waitUntil { locationProvider.active && headingProvider.activeCollectors == 1 }
      runOnIdle { headingProvider.headings.tryEmit(expectedHeading) }
      waitUntil { state?.lastLocation == expectedLocation && state.lastHeading == expectedHeading }

      runOnIdle { enabled = false }
      waitUntil {
        !locationProvider.active &&
          headingProvider.activeCollectors == 0 &&
          state?.status == LocationTrackingStatus.Stopped &&
          state.headingStatus == HeadingTrackingStatus.Stopped
      }
      assertEquals(expectedLocation, state?.lastLocation)
      assertEquals(expectedHeading, state?.lastHeading)
    }
  }

  @Test
  fun completedProviderUpdatesStopTracking() = withMainDispatcher {
    runComposeUiTest {
      val expected = location(13.0)
      val lifecycleOwner = ResumedLifecycleOwner()
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = FiniteLocationProvider(expected),
            lifecycleOwner = lifecycleOwner,
          )
      }

      waitUntil {
        state?.let { it.lastLocation == expected && it.status == LocationTrackingStatus.Stopped } ==
          true
      }
    }
  }

  @Test
  fun replacingProviderMovesHeadingCollectionToNewState() = withMainDispatcher {
    runComposeUiTest {
      val lifecycleOwner = ResumedLifecycleOwner()
      val headingProvider = MutableHeadingProvider()
      var provider by mutableStateOf<LocationProvider>(FiniteLocationProvider())
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = provider,
            headingProvider = headingProvider,
            lifecycleOwner = lifecycleOwner,
          )
      }
      val originalState = state

      runOnIdle { provider = FiniteLocationProvider() }
      waitUntil { state !== originalState }
      val expected = heading(90.0)
      runOnIdle { headingProvider.headings.tryEmit(expected) }

      waitUntil { state?.lastHeading == expected }
      assertNull(originalState?.lastHeading)
    }
  }

  @Test
  fun replacingTrackedStateMovesLocationCollectionToNewState() = runComposeUiTest {
    val first = LocationState()
    val second = LocationState()
    var trackedState by mutableStateOf(first)
    val observed = mutableListOf<LocationMeasurement>()

    setContent {
      LocationTrackingEffect(trackedState) {
        observed += currentLocation
      }
    }
    val firstLocation = location(13.0)
    runOnIdle { first.lastLocation = firstLocation }
    waitUntil { observed == listOf(firstLocation) }

    val secondLocation = location(14.0)
    runOnIdle {
      trackedState = second
      second.lastLocation = secondLocation
    }

    waitUntil { observed == listOf(firstLocation, secondLocation) }
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
private inline fun withMainDispatcher(block: () -> Unit) {
  Dispatchers.setMain(Dispatchers.Unconfined)
  try {
    block()
  } finally {
    Dispatchers.resetMain()
  }
}

private class ResumedLifecycleOwner : LifecycleOwner {
  private val registry =
    LifecycleRegistry.createUnsafe(this).apply {
      currentState = Lifecycle.State.RESUMED
    }

  override val lifecycle: Lifecycle = registry

  fun moveTo(state: Lifecycle.State) {
    registry.currentState = state
  }
}

private class FiniteLocationProvider(private vararg val locations: LocationMeasurement) :
  LocationProvider {
  override fun updates(request: LocationRequest): Flow<LocationEvent> = flow {
    for (location in locations) {
      emit(
        LocationEvent.Update(
          measurement = location,
          measurementMark = TimeSource.Monotonic.markNow(),
        ),
      )
    }
  }
}

private class ActiveLocationProvider(
  val location: LocationMeasurement,
  override val backendAvailability: LocationBackendAvailability =
    LocationBackendAvailability.Available,
) : LocationProvider {
  var active = false
  var stopCount = 0

  override fun updates(request: LocationRequest): Flow<LocationEvent> = flow {
    active = true
    try {
      emit(
        LocationEvent.Update(
          measurement = location,
          measurementMark = TimeSource.Monotonic.markNow(),
        ),
      )
      awaitCancellation()
    } finally {
      active = false
      stopCount += 1
    }
  }
}

private class MutablePermissionProvider(
  private val delegate: LocationProvider,
  initialPermission: LocationPermission = LocationPermission.NotGranted(canRequest = true),
) : LocationProvider {
  override val backendAvailability: LocationBackendAvailability
    get() = delegate.backendAvailability

  override val permission = MutableStateFlow(initialPermission)
  var requestCount = 0

  override fun requestPermission() {
    requestCount += 1
  }

  override fun updates(request: LocationRequest): Flow<LocationEvent> = delegate.updates(request)
}

private class MutableHeadingProvider : HeadingProvider {
  val headings = MutableSharedFlow<HeadingMeasurement>(extraBufferCapacity = 1)
  var activeCollectors = 0

  override fun updates(request: HeadingRequest): Flow<HeadingEvent> = flow {
    activeCollectors++
    try {
      emitAll(headings.map(HeadingEvent::Update))
    } finally {
      activeCollectors--
    }
  }
}

private class FailingHeadingProvider(private val failure: Throwable) : HeadingProvider {
  override fun updates(request: HeadingRequest): Flow<HeadingEvent> = flow { throw failure }
}

private class RetryableHeadingProvider(private val heading: HeadingMeasurement) : HeadingProvider {
  var attempts = 0

  override fun updates(request: HeadingRequest): Flow<HeadingEvent> = flow {
    attempts++
    if (attempts == 1) {
      emit(HeadingEvent.Update(heading))
      emit(HeadingEvent.Unavailable(reason = null))
      return@flow
    }
    emit(HeadingEvent.Update(heading))
    awaitCancellation()
  }
}

private class RetryableLocationProvider(private val location: LocationMeasurement) :
  LocationProvider {
  var attempts = 0

  override fun updates(request: LocationRequest): Flow<LocationEvent> = flow {
    attempts++
    if (attempts == 1) {
      emit(LocationEvent.Unavailable(LocationUnavailableReason.ServicesDisabled))
      return@flow
    }
    emit(
      LocationEvent.Update(
        measurement = location,
        measurementMark = TimeSource.Monotonic.markNow(),
      ),
    )
    awaitCancellation()
  }
}

private fun heading(degrees: Double): HeadingMeasurement =
  HeadingMeasurement(
    bearing = Bearing.North + degrees.degrees,
    reference = HeadingReference.TrueNorth,
    accuracy = null,
    measuredAt = Clock.System.now(),
  )

private fun location(longitude: Double): LocationMeasurement =
  LocationMeasurement(
    position = Position(longitude, 52.0),
    measuredAt = Clock.System.now(),
  )
