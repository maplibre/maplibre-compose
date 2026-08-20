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
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
      waitUntil { locationProvider.active && state?.location == locationProvider.location }

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
  fun permissionGrantStartsAndRevocationStopsOrientationUpdates() = withMainDispatcher {
    runComposeUiTest {
      val provider = MutablePermissionProvider(FiniteLocationProvider())
      val orientationProvider = MutableOrientationProvider()
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = provider,
            orientationProvider = orientationProvider,
            lifecycleOwner = ResumedLifecycleOwner(),
          )
      }

      waitUntil { state?.status == LocationTrackingStatus.WaitingForPermission }
      assertEquals(0, orientationProvider.orientation.subscriptionCount.value)

      runOnIdle {
        provider.permission.value =
          LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
      }
      waitUntil { orientationProvider.orientation.subscriptionCount.value == 1 }

      runOnIdle {
        provider.permission.value = LocationPermission.NotGranted(canRequest = false)
      }
      waitUntil { orientationProvider.orientation.subscriptionCount.value == 0 }
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

      waitUntil { provider.active && state?.location == provider.location }
      assertEquals(
        LocationPermission.Granted(LocationAccuracyAuthorization.Unknown),
        state?.permission,
      )
    }
  }

  @Test
  fun collectionContextAppliesToOrientationUpdates() = withMainDispatcher {
    runComposeUiTest {
      val orientationProvider = ContextRecordingOrientationProvider()

      setContent {
        rememberLocationState(
          provider = FiniteLocationProvider(),
          orientationProvider = orientationProvider,
          lifecycleOwner = ResumedLifecycleOwner(),
          coroutineContext = CoroutineName("location-updates"),
        )
      }

      waitUntil { orientationProvider.collectionName.isCompleted }
      assertEquals("location-updates", orientationProvider.collectionName.getCompleted())
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
        state?.let { it.location == expected && it.status == LocationTrackingStatus.Stopped } ==
          true
      }
    }
  }

  @Test
  fun replacingProviderMovesOrientationCollectionToNewState() = withMainDispatcher {
    runComposeUiTest {
      val lifecycleOwner = ResumedLifecycleOwner()
      val orientationProvider = MutableOrientationProvider()
      var provider by mutableStateOf<LocationProvider>(FiniteLocationProvider())
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = provider,
            orientationProvider = orientationProvider,
            lifecycleOwner = lifecycleOwner,
          )
      }
      val originalState = state

      runOnIdle { provider = FiniteLocationProvider() }
      waitUntil { state !== originalState }
      val expected =
        Orientation(
          orientation = BearingWithAccuracy(Bearing.North + 90.0.degrees, null),
          timestamp = TimeSource.Monotonic.markNow(),
        )
      runOnIdle { orientationProvider.orientation.value = expected }

      waitUntil { state?.orientation == expected }
      assertEquals(null, originalState?.orientation)
    }
  }

  @Test
  fun replacingTrackedStateMovesLocationCollectionToNewState() = runComposeUiTest {
    val first = LocationState()
    val second = LocationState()
    var trackedState by mutableStateOf(first)
    val observed = mutableListOf<Location>()

    setContent {
      LocationTrackingEffect(trackedState) {
        observed += currentLocation
      }
    }
    val firstLocation = location(13.0)
    runOnIdle { first.location = firstLocation }
    waitUntil { observed == listOf(firstLocation) }

    val secondLocation = location(14.0)
    runOnIdle {
      trackedState = second
      second.location = secondLocation
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
}

private class FiniteLocationProvider(private vararg val locations: Location) : LocationProvider {
  override fun updates(request: LocationRequest): Flow<LocationEvent> =
    flowOf(*locations.map(LocationEvent::Fix).toTypedArray())
}

private class ActiveLocationProvider(
  val location: Location,
  override val backendAvailability: LocationBackendAvailability =
    LocationBackendAvailability.Available,
) : LocationProvider {
  var active = false
  var stopCount = 0

  override fun updates(request: LocationRequest): Flow<LocationEvent> = flow {
    active = true
    try {
      emit(LocationEvent.Fix(location))
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

private class MutableOrientationProvider : OrientationProvider {
  override val orientation = MutableStateFlow<Orientation?>(null)
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ContextRecordingOrientationProvider : OrientationProvider {
  val collectionName = CompletableDeferred<String?>()
  override val orientation =
    object : StateFlow<Orientation?> {
      override val value: Orientation? = null
      override val replayCache: List<Orientation?> = listOf(null)

      override suspend fun collect(collector: FlowCollector<Orientation?>): Nothing {
        collectionName.complete(currentCoroutineContext()[CoroutineName]?.name)
        awaitCancellation()
      }
    }
}

private fun location(longitude: Double): Location =
  Location(
    position = PositionWithAccuracy(Position(longitude, 52.0), null),
    timestamp = TimeSource.Monotonic.markNow(),
  )
