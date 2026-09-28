package org.maplibre.compose.location

import android.Manifest.permission.ACCESS_FINE_LOCATION
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) // The permission request path needs a real Bundle.
@Config(sdk = [36], manifest = Config.NONE)
class AndroidLocationPermissionRequesterTest {
  @BeforeTest
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `close stops polling even while status is collected`() = runTest {
    var reads = 0
    val requester =
      AndroidLocationPermissionRequester(
        null,
        null,
        {
          reads++
          null
        },
        { null },
      )
    backgroundScope.launch { requester.status.collect {} }
    runCurrent()
    advanceTimeBy(1.seconds)
    runCurrent()
    assertEquals(3, reads)
    requester.close()
    advanceTimeBy(2.seconds)
    runCurrent()
    assertEquals(3, reads)
  }

  @Test
  fun `close removes the observer and prevents later refreshes`() {
    val owner = TestLifecycleOwner()
    var reads = 0
    val requester =
      AndroidLocationPermissionRequester(
        owner.lifecycle,
        null,
        {
          reads++
          null
        },
        { false },
      )
    owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
    owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    assertEquals(2, reads)
    assertEquals(1, owner.lifecycle.observerCount)

    requester.close()
    requester.close()
    assertEquals(0, owner.lifecycle.observerCount)
    owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    assertEquals(2, reads)
    assertFailsWith<IllegalStateException> { requester.requestForegroundPermission() }
  }

  @Test
  fun `activity destruction closes the requester`() {
    val owner = TestLifecycleOwner()
    val requester = AndroidLocationPermissionRequester(owner.lifecycle, null, { null }, { false })
    owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

    assertEquals(0, owner.lifecycle.observerCount)
    assertFailsWith<IllegalStateException> { requester.refresh() }
  }

  @Test
  fun `status resolves platform signals in precedence order`() {
    var granted: LocationAccuracyAuthorization? = null
    var rationale: Boolean? = false
    val registry = TestResultRegistry()
    val requester = AndroidLocationPermissionRequester(null, registry, { granted }, { rationale })
    assertEquals(LocationPermission.NotGranted(canRequest = true), requester.status.value)
    requester.requestForegroundPermission()
    registry.dispatchResult(registry.requestCode, mapOf(ACCESS_FINE_LOCATION to false))

    // Rows run in order: the denial above stays recorded until a rationale or grant clears it.
    listOf(
        Triple(null, false, LocationPermission.NotGranted(canRequest = false)),
        Triple(null, null, LocationPermission.NotGranted(canRequest = null)),
        Triple(null, true, LocationPermission.NotGranted(true, shouldShowRationale = true)),
        Triple(null, false, LocationPermission.NotGranted(canRequest = true)),
        Triple(
          LocationAccuracyAuthorization.Precise,
          true,
          LocationPermission.Granted(LocationAccuracyAuthorization.Precise),
        ),
        Triple(
          LocationAccuracyAuthorization.Approximate,
          null,
          LocationPermission.Granted(LocationAccuracyAuthorization.Approximate),
        ),
      )
      .forEach { (grantedAccuracy, shouldShowRationale, expected) ->
        granted = grantedAccuracy
        rationale = shouldShowRationale
        assertEquals(expected, requester.refresh(), "$grantedAccuracy, $shouldShowRationale")
      }
    requester.close()
  }

  private class TestResultRegistry : ActivityResultRegistry() {
    var requestCode = 0

    override fun <I, O> onLaunch(
      requestCode: Int,
      contract: ActivityResultContract<I, O>,
      input: I,
      options: ActivityOptionsCompat?,
    ) {
      this.requestCode = requestCode
    }
  }

  private class TestLifecycleOwner : LifecycleOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this)
  }
}
