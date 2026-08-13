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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
  fun requesterMisconfigurationPrecedesPermissionAndPreventsCollection() = withMainDispatcher {
    runComposeUiTest {
      val cause = IllegalStateException("multiple backends")
      val provider = ActiveLocationProvider(location(13.0))
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = provider,
            permissionRequester = MisconfiguredPermissionRequester(cause),
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
      val provider = ActiveLocationProvider(location(13.0))
      val requester = MutablePermissionRequester()
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = provider,
            permissionRequester = requester,
            lifecycleOwner = lifecycleOwner,
          )
      }

      waitUntil { state?.status == LocationTrackingStatus.WaitingForPermission }
      assertFalse(provider.active)
      runOnIdle { state?.requestPermission() }
      assertEquals(1, requester.requestCount)

      runOnIdle {
        requester.status.value = LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
      }
      waitUntil { provider.active && state?.location == provider.location }

      runOnIdle {
        requester.status.value = LocationPermission.NotGranted(canRequest = false)
      }
      waitUntil {
        !provider.active && state?.status == LocationTrackingStatus.WaitingForPermission
      }
      assertTrue(provider.stopCount > 0)
    }
  }

  @Test
  fun permissionGrantStartsAndRevocationStopsOrientationUpdates() = withMainDispatcher {
    runComposeUiTest {
      val requester = MutablePermissionRequester()
      val orientationProvider = MutableOrientationProvider()
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = FiniteLocationProvider(),
            permissionRequester = requester,
            orientationProvider = orientationProvider,
            lifecycleOwner = ResumedLifecycleOwner(),
          )
      }

      waitUntil { state?.status == LocationTrackingStatus.WaitingForPermission }
      assertEquals(0, orientationProvider.orientation.subscriptionCount.value)

      runOnIdle {
        requester.status.value = LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
      }
      waitUntil { orientationProvider.orientation.subscriptionCount.value == 1 }

      runOnIdle {
        requester.status.value = LocationPermission.NotGranted(canRequest = false)
      }
      waitUntil { orientationProvider.orientation.subscriptionCount.value == 0 }
    }
  }

  @Test
  fun replacingGrantedRequesterMovesLocationCollectionToNewState() = withMainDispatcher {
    runComposeUiTest {
      val provider = ActiveLocationProvider(location(13.0))
      var requester by
        mutableStateOf<LocationPermissionRequester>(
          MutablePermissionRequester(
            LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
          )
        )
      var state: LocationState? = null

      setContent {
        state =
          rememberLocationState(
            provider = provider,
            permissionRequester = requester,
            lifecycleOwner = ResumedLifecycleOwner(),
          )
      }
      waitUntil { state?.location == provider.location }
      val originalState = state

      runOnIdle {
        requester =
          MutablePermissionRequester(
            LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
          )
      }

      waitUntil { state !== originalState && state?.location == provider.location }
      assertTrue(provider.stopCount > 0)
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
            permissionRequester = GrantedPermissionRequester,
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
            permissionRequester = GrantedPermissionRequester,
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

private class ActiveLocationProvider(val location: Location) : LocationProvider {
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

private class MutablePermissionRequester(
  initialStatus: LocationPermission = LocationPermission.NotGranted(canRequest = true)
) : LocationPermissionRequester {
  override val status = MutableStateFlow(initialStatus)
  var requestCount = 0

  override fun requestForegroundPermission() {
    requestCount += 1
  }
}

private class MisconfiguredPermissionRequester(cause: Throwable) : LocationPermissionRequester {
  override val backendAvailability = LocationBackendAvailability.Misconfigured(cause)
  override val status =
    MutableStateFlow<LocationPermission>(LocationPermission.NotGranted(canRequest = null))

  override fun requestForegroundPermission() = Unit
}

private object GrantedPermissionRequester : LocationPermissionRequester {
  override val status =
    MutableStateFlow<LocationPermission>(
      LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
    )

  override fun requestForegroundPermission() = Unit
}

private class MutableOrientationProvider : OrientationProvider {
  override val orientation = MutableStateFlow<Orientation?>(null)
}

private fun location(longitude: Double): Location =
  Location(
    position = PositionWithAccuracy(Position(longitude, 52.0), null),
    timestamp = TimeSource.Monotonic.markNow(),
  )
