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
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class LocationStateTest {
  @Test
  fun completedProviderSessionStopsTracking() = withMainDispatcher {
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
    val first = LocationState(FiniteLocationProvider())
    val second = LocationState(FiniteLocationProvider())
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
  override val permission =
    FixedLocationPermissionController(
      LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
    )

  override fun updates(request: LocationRequest): Flow<LocationEvent> =
    flowOf(*locations.map(LocationEvent::Fix).toTypedArray())
}

private class MutableOrientationProvider : OrientationProvider {
  override val orientation = MutableStateFlow<Orientation?>(null)
}

private fun location(longitude: Double): Location =
  Location(
    position = PositionWithAccuracy(Position(longitude, 52.0), null),
    timestamp = TimeSource.Monotonic.markNow(),
  )
