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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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

      waitUntil {
        state?.status ==
          LocationTrackingStatus.Unavailable(LocationUnavailableReason.Misconfigured, cause)
      }
      assertFalse(provider.active)
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

      waitUntil { state?.status == LocationTrackingStatus.WaitingForPermission }
      assertFalse(locationProvider.active)
      runOnIdle { state?.requestPermission() }
      assertEquals(1, provider.requestCount)

      runOnIdle {
        provider.permission.value =
          LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
      }
      waitUntil { locationProvider.active && state?.lastReading == locationProvider.location }

      runOnIdle {
        provider.permission.value = LocationPermission.NotGranted(canRequest = false)
      }
      waitUntil {
        !locationProvider.active && state?.status == LocationTrackingStatus.WaitingForPermission
      }
      assertTrue(locationProvider.stopCount > 0)
    }
  }

  @Test
  fun permissionGrantStartsAndRevocationStopsHeadingUpdates() = withMainDispatcher {
    runComposeUiTest {
      val provider = MutablePermissionProvider(FiniteLocationProvider())
      val headingProvider = MutableHeadingProvider()
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = provider,
            headingProvider = headingProvider,
            lifecycleOwner = ResumedLifecycleOwner(),
          )
      }

      waitUntil { state?.status == LocationTrackingStatus.WaitingForPermission }
      assertEquals(0, headingProvider.activeCollectors)

      runOnIdle {
        provider.permission.value =
          LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
      }
      waitUntil { headingProvider.activeCollectors == 1 }

      runOnIdle {
        provider.permission.value = LocationPermission.NotGranted(canRequest = false)
      }
      waitUntil { headingProvider.activeCollectors == 0 }
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

      waitUntil { provider.active && state?.lastReading == provider.location }
      assertEquals(
        LocationPermission.Granted(LocationAccuracyAuthorization.Unknown),
        state?.permission,
      )
    }
  }

  @Test
  fun collectionContextAppliesToHeadingUpdates() = withMainDispatcher {
    runComposeUiTest {
      val headingProvider = ContextRecordingHeadingProvider()

      setContent {
        rememberLocationState(
          provider = FiniteLocationProvider(),
          headingProvider = headingProvider,
          lifecycleOwner = ResumedLifecycleOwner(),
          coroutineContext = CoroutineName("location-updates"),
        )
      }

      waitUntil { headingProvider.collectionName.isCompleted }
      assertEquals("location-updates", headingProvider.collectionName.getCompleted())
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
            it.lastReading == locationProvider.location &&
              it.headingStatus == HeadingTrackingStatus.Unavailable(failure)
          } == true
      }
      assertEquals(LocationTrackingStatus.Tracking, state?.status)
    }
  }

  @Test
  fun retryRestartsHeadingAfterFailure() = withMainDispatcher {
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

      waitUntil { state?.headingStatus is HeadingTrackingStatus.Unavailable }
      runOnIdle { state?.retry() }
      waitUntil {
        headingProvider.attempts == 2 &&
          state?.lastHeading == expected &&
          state.headingStatus == HeadingTrackingStatus.Tracking
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
      waitUntil { state?.lastReading == expectedLocation && state.lastHeading == expectedHeading }

      runOnIdle { enabled = false }
      waitUntil {
        !locationProvider.active &&
          headingProvider.activeCollectors == 0 &&
          state?.status == LocationTrackingStatus.Stopped &&
          state.headingStatus == HeadingTrackingStatus.Stopped
      }
      assertEquals(expectedLocation, state?.lastReading)
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
        state?.let { it.lastReading == expected && it.status == LocationTrackingStatus.Stopped } ==
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
      val expected =
        Heading(
          bearing = Bearing.North + 90.0.degrees,
          accuracy = null,
          measuredAt = Clock.System.now(),
        )
      runOnIdle { headingProvider.headings.tryEmit(expected) }

      waitUntil { state?.lastHeading == expected }
      assertEquals(null, originalState?.lastHeading)
    }
  }

  @Test
  fun replacingTrackedStateMovesLocationCollectionToNewState() = runComposeUiTest {
    val first = LocationState()
    val second = LocationState()
    var trackedState by mutableStateOf(first)
    val observed = mutableListOf<LocationReading>()

    setContent {
      LocationTrackingEffect(trackedState) {
        observed += currentReading
      }
    }
    val firstLocation = location(13.0)
    runOnIdle { first.lastReading = firstLocation }
    waitUntil { observed == listOf(firstLocation) }

    val secondLocation = location(14.0)
    runOnIdle {
      trackedState = second
      second.lastReading = secondLocation
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

private class FiniteLocationProvider(private vararg val locations: LocationReading) :
  LocationProvider {
  override fun updates(request: LocationRequest): Flow<LocationEvent> =
    flowOf(*locations.map(LocationEvent::Update).toTypedArray())
}

private class ActiveLocationProvider(
  val location: LocationReading,
  override val backendAvailability: LocationBackendAvailability =
    LocationBackendAvailability.Available,
) : LocationProvider {
  var active = false
  var stopCount = 0

  override fun updates(request: LocationRequest): Flow<LocationEvent> = flow {
    active = true
    try {
      emit(LocationEvent.Update(location))
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
  val headings = MutableSharedFlow<Heading>(extraBufferCapacity = 1)
  var activeCollectors = 0

  override fun updates(request: HeadingRequest): Flow<Heading> = flow {
    activeCollectors++
    try {
      emitAll(headings)
    } finally {
      activeCollectors--
    }
  }
}

private class ContextRecordingHeadingProvider : HeadingProvider {
  val collectionName = CompletableDeferred<String?>()

  override fun updates(request: HeadingRequest): Flow<Heading> = flow {
    collectionName.complete(currentCoroutineContext()[CoroutineName]?.name)
    awaitCancellation()
  }
}

private class FailingHeadingProvider(private val failure: Throwable) : HeadingProvider {
  override fun updates(request: HeadingRequest): Flow<Heading> = flow { throw failure }
}

private class RetryableHeadingProvider(private val heading: Heading) : HeadingProvider {
  var attempts = 0

  override fun updates(request: HeadingRequest): Flow<Heading> = flow {
    attempts++
    if (attempts == 1) throw IllegalStateException("sensor failed")
    emit(heading)
    awaitCancellation()
  }
}

private fun heading(degrees: Double): Heading =
  Heading(
    bearing = Bearing.North + degrees.degrees,
    accuracy = null,
    measuredAt = Clock.System.now(),
  )

private fun location(longitude: Double): LocationReading =
  LocationReading(
    position = Position(longitude, 52.0),
    measuredAt = Clock.System.now(),
  )
