package org.maplibre.compose.hms

import android.location.Location
import android.os.Looper
import com.huawei.hmf.tasks.Task
import com.huawei.hmf.tasks.TaskCompletionSource
import com.huawei.hms.location.FusedLocationProviderClient
import com.huawei.hms.location.HWLocation
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
  fun securityFailureRecoversWithoutPermissionDelegate() = runTest {
    val client = TestClient().apply { denied = true }
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
    client.denied = false
    client.cachedLocation = Location("fused")
    advanceTimeBy(1.seconds)
    runCurrent()
    shadowOf(Looper.getMainLooper()).idle()
    runCurrent()
    assertIs<LocationEvent.Update>(events.last())
    collection.cancelAndJoin()
    assertTrue(client.callbacks.isEmpty())
  }

  @Test
  fun collectorRecoversAfterPermissionChanges() = runTest {
    val permission = MutableStateFlow<LocationPermission>(LocationPermission.NotGranted(false))
    val delegate =
      object : LocationProvider {
        override val permission = permission

        override fun updates(request: LocationRequest) =
          error("Permission delegate must not receive locations")

        override fun requestPermission() = error("Collection must not prompt")
      }
    val client = TestClient()
    val provider = FusedLocationProvider(client, delegate)
    val events = mutableListOf<LocationEvent>()
    val collection = backgroundScope.launch {
      provider.updates(LocationRequest()).collect(events::add)
    }
    runCurrent()
    assertEquals(
      LocationUnavailableReason.PermissionDenied,
      assertIs<LocationEvent.Unavailable>(events.last()).reason,
    )
    assertTrue(client.callbacks.isEmpty())
    permission.value = LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
    runCurrent()
    assertEquals(1, client.callbacks.size)
    fun sendLocation() {
      val result = LocationResult.create(listOf(HWLocation()))
      client.callbacks.toList().forEach { it.onLocationResult(result) }
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
    assertTrue(client.callbacks.isEmpty())
    permission.value = LocationPermission.Granted(LocationAccuracyAuthorization.Approximate)
    runCurrent()
    assertEquals(1, client.callbacks.size)
    sendLocation()
    runCurrent()
    assertIs<LocationEvent.Update>(events.last())
    collection.cancelAndJoin()
    assertTrue(client.callbacks.isEmpty())
    provider.close()
  }
}

private fun <T> completed(value: T?): Task<T> =
  TaskCompletionSource<T>().apply { setResult(value) }.task

private class TestClient : FusedLocationProviderClient(RuntimeEnvironment.getApplication()) {
  var denied = false
  var cachedLocation: Location? = null
  val callbacks = mutableSetOf<LocationCallback>()

  override fun getLastLocation(): Task<Location> {
    if (denied) throw SecurityException("denied")
    return completed(cachedLocation)
  }

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
