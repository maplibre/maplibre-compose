package org.maplibre.compose.gms

import com.google.android.gms.location.DeviceOrientation
import com.google.android.gms.location.DeviceOrientationListener
import com.google.android.gms.location.FusedOrientationProviderClient
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.location.HeadingReference
import org.maplibre.compose.location.HeadingRequest
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.inDegrees

@OptIn(ExperimentalCoroutinesApi::class)
class FusedHeadingProviderTest {

  @Test
  fun `input heading is preserved as clockwise bearing`() = runTest {
    for (heading in listOf(0f, 45f, 90f, 123.45f, 180f, 270f, 359.99f)) {
      val provider =
        FusedHeadingProvider(
          orientationClient = fakeClient(heading),
          elapsedRealtimeNanos = { 0L },
        )

      val result = provider.updates(HeadingRequest(Duration.ZERO)).first()
      val bearing = result.bearing

      assertEquals(heading.toDouble(), Bearing.North.clockwiseRotationTo(bearing).inDegrees, 1e-10)
      assertEquals(HeadingReference.TrueOrMagneticNorth, result.reference)
    }
  }

  @Test
  fun `complete ignorance has unknown accuracy`() = runTest {
    val provider =
      FusedHeadingProvider(
        orientationClient = fakeClient(heading = 90f, error = 180f),
        elapsedRealtimeNanos = { 0L },
      )

    val result = provider.updates(HeadingRequest(Duration.ZERO)).first()

    assertNull(result.accuracy)
  }

  @Test
  fun `registration failure terminates the flow`() = runTest {
    val failure = IllegalStateException("registration failed")
    val provider =
      FusedHeadingProvider(
        orientationClient = fakeClient(failure = failure),
        elapsedRealtimeNanos = { 0L },
      )

    val thrown =
      assertFailsWith<IllegalStateException> {
        provider.updates(HeadingRequest(Duration.ZERO)).first()
      }

    assertEquals(failure.message, thrown.message)
  }

  @Test
  fun `cancellation removes callback after delayed registration completes`() = runTest {
    val registration = TaskCompletionSource<Void>()
    val removed = CompletableDeferred<Unit>()
    val provider =
      FusedHeadingProvider(
        orientationClient =
          fakeClient(
            registration = registration.task,
            onRemove = { removed.complete(Unit) },
          ),
        elapsedRealtimeNanos = { 0L },
        executor = DIRECT_EXECUTOR,
      )
    val collection = launch { provider.updates(HeadingRequest(Duration.ZERO)).collect {} }

    runCurrent()
    collection.cancelAndJoin()
    assertFalse(removed.isCompleted)

    registration.setResult(null)
    assertTrue(removed.isCompleted)
  }

  private fun fakeClient(
    heading: Float = 0f,
    error: Float = 5f,
    failure: Exception? = null,
    registration: Task<Void>? = null,
    onRemove: () -> Unit = {},
  ): FusedOrientationProviderClient {
    val deviceOrientation =
      DeviceOrientation.Builder(floatArrayOf(0f, 0f, 0f, 1f), heading, error, 0L).build()

    return Proxy.newProxyInstance(
      FusedOrientationProviderClient::class.java.classLoader,
      arrayOf(FusedOrientationProviderClient::class.java),
      object : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any {
          return when (method.name) {
            "requestOrientationUpdates" -> {
              if (registration != null) {
                registration
              } else if (failure == null) {
                (args!![2] as DeviceOrientationListener).onDeviceOrientationChanged(
                  deviceOrientation
                )
                Tasks.forResult(null)
              } else {
                Tasks.forException(failure)
              }
            }
            "removeOrientationUpdates" -> {
              onRemove()
              Tasks.forResult(null)
            }
            else -> throw UnsupportedOperationException(method.name)
          }
        }
      },
    ) as FusedOrientationProviderClient
  }

  private companion object {
    val DIRECT_EXECUTOR = Executor { it.run() }
  }
}
