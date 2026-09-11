package org.maplibre.compose.location

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.app.Application
import android.location.Location
import android.location.LocationManager
import androidx.activity.ComponentActivity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION") // Inspect registrations to verify per-collector cleanup.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], manifest = Config.NONE)
class AndroidLocationProviderTest {
  private val dispatcher = StandardTestDispatcher()
  private lateinit var application: Application
  private lateinit var manager: LocationManager

  @BeforeTest
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    application = RuntimeEnvironment.getApplication()
    manager = application.getSystemService(LocationManager::class.java)
    shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    shadowOf(manager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
    deny()
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun applicationContextRecoversAcrossDenialAndRevocationWithoutRestartingCollectors() =
    runTest(dispatcher) {
      val provider = AndroidLocationProvider(application)
      val events = mutableListOf<LocationEvent>()
      val collection = backgroundScope.launch {
        provider.updates(LocationRequest()).collect(events::add)
      }
      runCurrent()
      assertDenied(events.last())
      assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())

      grant()
      advanceTimeBy(1.seconds)
      runCurrent()
      sendLocation()
      runCurrent()
      assertIs<LocationEvent.Update>(events.last())

      deny()
      advanceTimeBy(1.seconds)
      runCurrent()
      assertDenied(events.last())
      assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())

      grant()
      advanceTimeBy(1.seconds)
      runCurrent()
      sendLocation()
      runCurrent()
      assertIs<LocationEvent.Update>(events.last())
      assertTrue(collection.isActive)

      collection.cancelAndJoin()
      assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
      provider.close()
    }

  @Test
  fun collectionRefreshesStalePermissionBeforeEmittingFromAnotherDispatcher() =
    runTest(dispatcher) {
      val provider = AndroidLocationProvider(application)
      grant()
      val events = mutableListOf<LocationEvent>()
      val collection =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          provider.updates().collect(events::add)
        }
      // Collection can run now, but the main-thread refresh has not run yet.
      assertTrue(events.isEmpty())
      runCurrent()
      sendLocation()
      runCurrent()
      assertIs<LocationEvent.Update>(events.single())
      collection.cancelAndJoin()
      provider.close()
    }

  @Test
  fun activityResumeRefreshesPermissionBeforeTheNextPoll() =
    runTest(dispatcher) {
      val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
      val provider = AndroidLocationProvider(activity.get())
      val events = mutableListOf<LocationEvent>()
      val collection = backgroundScope.launch {
        provider.updates(LocationRequest()).collect(events::add)
      }
      runCurrent()
      assertDenied(events.last())

      activity.pause()
      grant()
      activity.resume()
      runCurrent()
      sendLocation()
      runCurrent()
      assertIs<LocationEvent.Update>(events.last())

      collection.cancelAndJoin()
      provider.close()
      activity.pause().stop().destroy()
    }

  @Test
  fun collectorsOwnIndependentRegistrationsAndCancellationWhileDeniedCannotRestart() =
    runTest(dispatcher) {
      val provider = AndroidLocationProvider(application)
      val first = backgroundScope.launch { provider.updates(LocationRequest()).collect {} }
      val second = backgroundScope.launch { provider.updates(LocationRequest()).collect {} }
      runCurrent()
      grant()
      advanceTimeBy(1.seconds)
      runCurrent()
      assertEquals(2, shadowOf(manager).locationUpdateListeners.size)
      first.cancelAndJoin()
      assertEquals(1, shadowOf(manager).locationUpdateListeners.size)
      deny()
      advanceTimeBy(1.seconds)
      runCurrent()
      assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
      second.cancelAndJoin()
      runCurrent()
      grant()
      advanceTimeBy(2.seconds)
      runCurrent()
      assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
      // No subscribers means no polling; collecting again refreshes the stale snapshot.
      assertIs<LocationPermission.NotGranted>(provider.permission.value)
      val third = backgroundScope.launch { provider.updates(LocationRequest()).collect {} }
      runCurrent()
      assertEquals(1, shadowOf(manager).locationUpdateListeners.size)
      third.cancelAndJoin()
      provider.close()
    }

  private fun grant() {
    shadowOf(application).grantPermissions(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
  }

  private fun deny() {
    shadowOf(application).denyPermissions(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
  }

  private fun assertDenied(event: LocationEvent) {
    assertEquals(
      LocationUnavailableReason.PermissionDenied,
      assertIs<LocationEvent.Unavailable>(event).reason,
    )
  }

  private fun sendLocation() {
    val listeners = shadowOf(manager).locationUpdateListeners
    assertEquals(1, listeners.size)
    listeners
      .single()
      .onLocationChanged(
        Location(LocationManager.GPS_PROVIDER).apply {
          latitude = 52.0
          longitude = 13.0
          accuracy = 3f
          time = System.currentTimeMillis()
          elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }
      )
  }
}
