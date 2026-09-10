package org.maplibre.compose.hms

import android.location.Location
import android.os.Looper
import com.huawei.hmf.tasks.Task
import com.huawei.hmf.tasks.TaskCompletionSource
import com.huawei.hms.location.FusedLocationProviderClient
import com.huawei.hms.location.LocationCallback
import com.huawei.hms.location.LocationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.maplibre.compose.location.LocationAccuracyAuthorization
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationUnavailableReason
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class FusedLocationPermissionRecoveryTest {
  @Test
  fun securityFailureRetriesWithoutAPermissionDelegateAndCancellationStopsRetrying() = runTest {
    var denied = true
    var reads = 0
    var removals = 0
    val client =
      object : FusedLocationProviderClient(RuntimeEnvironment.getApplication()) {
        override fun getLastLocation(): Task<Location> {
          reads++
          if (denied) throw SecurityException("denied")
          return completed(Location("fused").apply { time = System.currentTimeMillis() })
        }

        override fun requestLocationUpdates(
          request: com.huawei.hms.location.LocationRequest,
          callback: LocationCallback,
          looper: Looper,
        ): Task<Void> = completed(null)

        override fun removeLocationUpdates(callback: LocationCallback): Task<Void> {
          removals++
          return completed(null)
        }
      }
    val provider = FusedLocationProvider(client, null)
    val events = mutableListOf<LocationEvent>()
    val collection = backgroundScope.launch {
      provider.updates(LocationRequest()).collect(events::add)
    }
    runCurrent()
    assertEquals(
      LocationUnavailableReason.PermissionDenied,
      assertIs<LocationEvent.Unavailable>(events.last()).reason,
    )
    denied = false
    advanceTimeBy(1.seconds)
    runCurrent()
    shadowOf(Looper.getMainLooper()).idle()
    runCurrent()
    assertIs<LocationEvent.Update>(events.last())
    assertEquals(2, reads)
    collection.cancelAndJoin()
    advanceTimeBy(2.seconds)
    runCurrent()
    assertEquals(2, reads)
    // A failed start and the successful registration each release their callback.
    assertEquals(2, removals)
  }

  @Test
  fun cancelledCollectorRemovesCallbackAfterDelayedRegistrationCompletes() = runTest {
    val registration = TaskCompletionSource<Void>()
    var removals = 0
    val client =
      object : FusedLocationProviderClient(RuntimeEnvironment.getApplication()) {
        override fun getLastLocation(): Task<Location> = completed(null)

        override fun requestLocationUpdates(
          request: com.huawei.hms.location.LocationRequest,
          callback: LocationCallback,
          looper: Looper,
        ): Task<Void> = registration.task

        override fun removeLocationUpdates(callback: LocationCallback): Task<Void> {
          removals++
          return completed(null)
        }
      }
    val provider = FusedLocationProvider(client, null)
    val collection = backgroundScope.launch { provider.updates(LocationRequest()).collect {} }
    runCurrent()
    collection.cancelAndJoin()
    assertEquals(0, removals)
    registration.setResult(null)
    assertEquals(1, removals)
  }

  @Test
  fun collectorsRecoverWithoutPromptsAndRemoveOnlyTheirOwnCallbacks() = runTest {
    val permission = MutableStateFlow<LocationPermission>(LocationPermission.NotGranted(false))
    val delegate =
      object : LocationProvider {
        override val permission = permission

        override fun updates(request: LocationRequest) =
          error("Permission delegate must not receive locations")

        override fun requestPermission() = error("Collection must not prompt")
      }
    val callbacks = mutableSetOf<LocationCallback>()
    val client =
      object : FusedLocationProviderClient(RuntimeEnvironment.getApplication()) {
        override fun getLastLocation(): Task<Location> = completed(null)

        override fun requestLocationUpdates(
          request: com.huawei.hms.location.LocationRequest,
          callback: LocationCallback,
          looper: Looper,
        ): Task<Void> {
          callbacks += callback
          return completed(null)
        }

        override fun removeLocationUpdates(callback: LocationCallback): Task<Void> {
          callbacks -= callback
          return completed(null)
        }
      }
    val provider = FusedLocationProvider(client, delegate)
    val events = mutableListOf<LocationEvent>()
    val first = backgroundScope.launch { provider.updates(LocationRequest()).collect(events::add) }
    val second = backgroundScope.launch { provider.updates(LocationRequest()).collect {} }
    runCurrent()
    assertEquals(
      LocationUnavailableReason.PermissionDenied,
      assertIs<LocationEvent.Unavailable>(events.last()).reason,
    )
    assertTrue(callbacks.isEmpty())
    permission.value = LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
    runCurrent()
    assertEquals(2, callbacks.size)
    fun sendLocation() {
      val result =
        LocationResult.create(
          listOf(
            com.huawei.hms.location.HWLocation().apply {
              latitude = 52.0
              longitude = 13.0
              accuracy = 3f
              time = System.currentTimeMillis()
            }
          )
        )
      callbacks.toList().forEach { it.onLocationResult(result) }
    }
    sendLocation()
    runCurrent()
    assertIs<LocationEvent.Update>(events.last())
    permission.value = LocationPermission.NotGranted(false)
    runCurrent()
    assertEquals(
      LocationUnavailableReason.PermissionDenied,
      assertIs<LocationEvent.Unavailable>(events.last()).reason,
    )
    assertTrue(callbacks.isEmpty())
    second.cancelAndJoin()
    permission.value = LocationPermission.Granted(LocationAccuracyAuthorization.Approximate)
    runCurrent()
    assertEquals(1, callbacks.size)
    sendLocation()
    runCurrent()
    assertIs<LocationEvent.Update>(events.last())
    first.cancelAndJoin()
    assertTrue(callbacks.isEmpty())
    provider.close()
  }
}

private fun <T> completed(value: T?): Task<T> =
  TaskCompletionSource<T>().apply { setResult(value) }.task
