package org.maplibre.compose.gms

import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.maplibre.compose.location.LocationRequest

@OptIn(ExperimentalCoroutinesApi::class)
class FusedLocationProviderTest {
  @Test
  fun `cancellation removes callback after delayed registration completes`() = runTest {
    val registration = TaskCompletionSource<Void>()
    val removed = CompletableDeferred<Unit>()
    val provider = FusedLocationProvider(fakeClient(registration, removed))
    val collection = launch { provider.updates(LocationRequest()).collect {} }

    runCurrent()
    collection.cancelAndJoin()
    assertFalse(removed.isCompleted)

    registration.setResult(null)
    withContext(Dispatchers.Default) { withTimeout(5.seconds) { removed.await() } }
  }

  private fun fakeClient(
    registration: TaskCompletionSource<Void>,
    removed: CompletableDeferred<Unit>,
  ): FusedLocationProviderClient =
    Proxy.newProxyInstance(
      FusedLocationProviderClient::class.java.classLoader,
      arrayOf(FusedLocationProviderClient::class.java),
      object : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any =
          when (method.name) {
            "getLastLocation" -> Tasks.forResult(null)
            "requestLocationUpdates" -> registration.task
            "removeLocationUpdates" -> {
              removed.complete(Unit)
              Tasks.forResult(null)
            }
            else -> throw UnsupportedOperationException(method.name)
          }
      },
    ) as FusedLocationProviderClient
}
