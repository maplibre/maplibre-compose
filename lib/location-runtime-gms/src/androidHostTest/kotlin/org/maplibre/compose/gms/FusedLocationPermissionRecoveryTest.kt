package org.maplibre.compose.gms

import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.tasks.Tasks
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
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
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class FusedLocationPermissionRecoveryTest {
  @Test
  fun securityFailureRecoversWithoutPermissionDelegate() = runTest {
    val client = TestClient().apply { denied = true }
    val provider = FusedLocationProvider(client.delegate, null, Executor { it.run() })
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
    client.lastLocation = Location("fused")
    advanceTimeBy(1.seconds)
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
    val provider = FusedLocationProvider(client.delegate, delegate, Executor { it.run() })
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
      val result = LocationResult.create(listOf(Location("fused")))
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

private class TestClient {
  var denied = false
  var lastLocation: Location? = null
  val callbacks = mutableSetOf<LocationCallback>()
  val delegate =
    Proxy.newProxyInstance(
      FusedLocationProviderClient::class.java.classLoader,
      arrayOf(FusedLocationProviderClient::class.java),
    ) { _, method, args ->
      when (method.name) {
        "getLastLocation" -> {
          if (denied) throw SecurityException("denied")
          Tasks.forResult(lastLocation)
        }
        "requestLocationUpdates" -> {
          callbacks += args[2] as LocationCallback
          Tasks.forResult<Void>(null)
        }
        "removeLocationUpdates" -> {
          callbacks -= args[0] as LocationCallback
          Tasks.forResult<Void>(null)
        }
        else -> error(method.name)
      }
    } as FusedLocationProviderClient
}
