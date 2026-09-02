package org.maplibre.compose.map

import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.testing.GlJsMapFixture

@OptIn(DelicateMapApi::class, ExperimentalTestApi::class)
class BrowserPlatformMapAccessTest {
  @Test
  fun web_access_requires_a_current_presentation() = runBrowserMapTest {
    val runtime = createMapRuntime(MapRuntimeOptions())
    val state = runtime.createMapState()

    val failure = assertFailsWith<IllegalStateException> { state.withPlatformMap { map.getZoom() } }

    assertEquals("Platform map access requires an attached Web map surface", failure.message)
    assertNull(state.currentMapAttachment)
    runtime.close()
    runtime.awaitClosed()
  }

  @Test
  fun web_access_uses_the_current_attached_engine_map() = runBrowserMapTest {
    val fixture = GlJsMapFixture(MapExtent.fromLogical(200, 100, 1.0))
    try {
      fixture.awaitMapReady()

      val zoom = fixture.state.withPlatformMap { map.getZoom() }

      assertEquals(0.0, zoom)
      assertTrue(fixture.state.currentMapAttachment?.isValid == true)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun a_web_invocation_queued_for_a_departed_lease_does_not_run() = runBrowserMapTest {
    val fixture = GlJsMapFixture(MapExtent.fromLogical(200, 100, 1.0))
    try {
      var callbackRan = false
      supervisorScope {
        val access =
          async(start = CoroutineStart.UNDISPATCHED) {
            fixture.state.withPlatformMap {
              callbackRan = true
              map.getZoom()
            }
          }

        fixture.detachPresentationForTest()

        val failure = assertFailsWith<IllegalStateException> { access.await() }
        assertEquals("The Web platform map changed before access could begin", failure.message)
      }
      assertFalse(callbackRan)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun cancelling_a_queued_web_invocation_prevents_its_callback() = runBrowserMapTest {
    val fixture = GlJsMapFixture(MapExtent.fromLogical(200, 100, 1.0))
    try {
      var callbackRan = false
      supervisorScope {
        val access =
          async(start = CoroutineStart.UNDISPATCHED) {
            fixture.state.withPlatformMap {
              callbackRan = true
              map.getZoom()
            }
          }

        access.cancel()
        assertFailsWith<CancellationException> { access.await() }
      }
      fixture.awaitMapReady()
      assertFalse(callbackRan)
    } finally {
      fixture.close()
    }
  }

  @Test
  fun closing_from_a_queued_web_callback_makes_the_first_frame_inert() = runBrowserMapTest {
    val fixture = GlJsMapFixture(MapExtent.fromLogical(200, 100, 1.0))
    try {
      supervisorScope {
        val access =
          async(start = CoroutineStart.UNDISPATCHED) {
            fixture.state.withPlatformMap {
              fixture.state.close()
              map.getZoom()
            }
          }

        assertFalse(fixture.renderFrameForTest())

        assertEquals(0.0, access.await())
        assertTrue(fixture.state.isClosed)
      }
    } finally {
      fixture.close()
    }
  }
}
