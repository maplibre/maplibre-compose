package org.maplibre.compose.location

import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class AndroidLocationPermissionRequestTest {
  @Test
  fun reentrantCloseDuringPermissionRefreshPreventsLaunch() {
    val registry = TestResultRegistry()
    var rationale = false
    val requester = AndroidLocationPermissionRequester(null, registry, { null }, { rationale })
    val observer =
      CoroutineScope(Dispatchers.Unconfined).launch {
        requester.status.drop(1).collect { requester.close() }
      }
    try {
      rationale = true
      requester.requestForegroundPermission()
      assertEquals(0, registry.launches)
      assertFailsWith<IllegalStateException> { requester.refresh() }
    } finally {
      observer.cancel()
      requester.close()
    }
  }

  @Test
  fun failedOffMainCloseCanStillDisposeOnMain() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val owner =
      object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
      }
    lateinit var requester: AndroidLocationPermissionRequester
    instrumentation.runOnMainSync {
      requester = AndroidLocationPermissionRequester(owner.lifecycle, null, { null }, { false })
      assertEquals(1, owner.lifecycle.observerCount)
    }

    assertFailsWith<IllegalStateException> { requester.close() }

    instrumentation.runOnMainSync {
      requester.close()
      assertEquals(0, owner.lifecycle.observerCount)
    }
  }

  @Test
  fun closeUnregistersPendingRequestBeforeResultArrives() {
    val registry = TestResultRegistry()
    var reads = 0
    val requester =
      AndroidLocationPermissionRequester(
        null,
        registry,
        {
          reads++
          null
        },
        { false },
      )
    requester.requestForegroundPermission()
    requester.requestForegroundPermission()
    assertEquals(1, registry.launches)
    val readsBeforeClose = reads
    val permissionBeforeClose = requester.status.value

    requester.close()
    registry.dispatchResult(registry.requestCode, mapOf("fine" to false))

    assertEquals(readsBeforeClose, reads)
    assertEquals(permissionBeforeClose, requester.status.value)
  }

  @Test
  fun failedLaunchAllowsAnotherRequest() {
    val registry = TestResultRegistry()
    val requester = AndroidLocationPermissionRequester(null, registry, { null }, { false })
    registry.failLaunch = true
    assertFailsWith<IllegalStateException> { requester.requestForegroundPermission() }
    registry.failLaunch = false

    requester.requestForegroundPermission()
    registry.dispatchResult(registry.requestCode, mapOf("fine" to false))

    assertEquals(2, registry.launches)
    assertEquals(LocationPermission.NotGranted(canRequest = false), requester.status.value)
    requester.close()
  }

  private class TestResultRegistry : ActivityResultRegistry() {
    var launches = 0
    var requestCode = 0
    var failLaunch = false

    override fun <I, O> onLaunch(
      requestCode: Int,
      contract: ActivityResultContract<I, O>,
      input: I,
      options: ActivityOptionsCompat?,
    ) {
      launches++
      this.requestCode = requestCode
      check(!failLaunch) { "Launch failed" }
    }
  }
}
