package org.maplibre.compose.gms

import com.google.android.gms.location.DeviceOrientation
import com.google.android.gms.location.DeviceOrientationListener
import com.google.android.gms.location.DeviceOrientationRequest
import com.google.android.gms.location.FusedOrientationProviderClient
import com.google.android.gms.tasks.Tasks
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.inDegrees

@OptIn(ExperimentalCoroutinesApi::class)
class FusedOrientationProviderTest {

  @Test
  fun `input heading is preserved as clockwise bearing`() = runTest {
    for (heading in listOf(0f, 45f, 90f, 123.45f, 180f, 270f, 359.99f)) {
      val provider =
        FusedOrientationProvider(
          orientationClient = fakeClient(heading),
          deviceOrientationRequest = DeviceOrientationRequest.Builder(1000L).build(),
          coroutineScope = backgroundScope,
          sharingStarted = SharingStarted.Eagerly,
        )

      runCurrent()
      advanceTimeBy(2)
      val result = provider.orientation.first { it != null }!!
      val bearing = result.orientation!!.value

      assertEquals(heading.toDouble(), Bearing.North.clockwiseRotationTo(bearing).inDegrees, 1e-10)
    }
  }

  private fun fakeClient(heading: Float): FusedOrientationProviderClient {
    val deviceOrientation =
      DeviceOrientation.Builder(floatArrayOf(0f, 0f, 0f, 1f), heading, 5f, 0L).build()

    return Proxy.newProxyInstance(
      FusedOrientationProviderClient::class.java.classLoader,
      arrayOf(FusedOrientationProviderClient::class.java),
      object : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any {
          return when (method.name) {
            "requestOrientationUpdates" -> {
              (args!![2] as DeviceOrientationListener).onDeviceOrientationChanged(deviceOrientation)
              Tasks.forResult(null)
            }
            "removeOrientationUpdates" -> Tasks.forResult(null)
            else -> throw UnsupportedOperationException(method.name)
          }
        }
      },
    ) as FusedOrientationProviderClient
  }
}
