@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.CoreLocation.CLLocationManager

class AppleLocationPermissionRequesterTest {
  @Test
  fun authorization_callback_accepts_another_wrapper_of_the_same_native_manager() {
    val manager = CLLocationManager()
    var permission: LocationPermission = LocationPermission.NotGranted(canRequest = true)
    AppleLocationPermissionRequester(manager) { permission }
      .use { requester ->
        assertEquals(permission, requester.status.value)
        permission = LocationPermission.Granted(LocationAccuracyAuthorization.Precise)
        val callbackManager = interpretObjCPointer<CLLocationManager>(manager.objcPtr())
        checkNotNull(manager.delegate).locationManagerDidChangeAuthorization(callbackManager)
        assertEquals(permission, requester.status.value)

        permission = LocationPermission.NotGranted(canRequest = false)
        checkNotNull(manager.delegate).locationManagerDidChangeAuthorization(CLLocationManager())
        assertEquals(
          LocationPermission.Granted(LocationAccuracyAuthorization.Precise),
          requester.status.value,
        )
      }
  }

  @Test
  fun failedOffMainCloseCanStillDisposeOnMain() = runTest {
    val manager = CLLocationManager()
    val requester = AppleLocationPermissionRequester(manager)
    try {
      withContext(Dispatchers.Default) {
        assertFailsWith<IllegalStateException> { requester.close() }
      }
      assertNotNull(manager.delegate)
    } finally {
      requester.close()
    }
    assertNull(manager.delegate)
  }
}
