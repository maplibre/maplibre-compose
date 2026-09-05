package org.maplibre.compose.location

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidLocationPermissionRequesterTest {
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

  private class TestLifecycleOwner : LifecycleOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this)
  }
}
