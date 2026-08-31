package org.maplibre.compose.mlnffi

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.ReusableContentHost
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Host retain / replace / remount against [FakeMlnFfiMapHost]. These cases prove acquire / draw /
 * close order. They do not run MapLibre.
 *
 * The Android SurfaceView frame after a replace stays in `AndroidSurfaceReplacementTest`.
 */
@OptIn(ExperimentalTestApi::class)
class MlnFfiMapSurfaceReplacementTest {

  @Test
  fun a_stable_surface_keeps_one_host_across_recompositions() = runFfiComposeUiTest {
    val renderer = RecordingMlnFfiMapRenderer()
    val factory = FakeMlnFfiMapHostFactory()
    val hostResult = factory.create(factory.bridges.single())
    var unused by mutableStateOf(0)

    setContent {
      unused
      MlnFfiMapSurface(renderer, hostResult, Modifier.size(64.dp))
    }
    val host = factory.created.single()
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { host.drawRecords.isNotEmpty() }
    assertTrue(host.acquireCount >= 1)
    assertEquals(0, renderer.closeCount)
    assertFalse(host.closed)

    unused = 1
    waitForIdle()

    assertEquals(1, factory.created.size)
    assertSame(host, factory.created.single())
    assertFalse(host.closed)
    assertEquals(0, renderer.closeCount)
    assertTrue(host.acquireCount >= 1)
    assertTrue(host.drawRecords.isNotEmpty())
    assertTrue(host.leakedFrames.isEmpty())
  }

  @Test
  fun replacing_the_composed_surface_closes_the_old_host_and_acquires_the_new_one() =
    runFfiComposeUiTest {
      val factory = FakeMlnFfiMapHostFactory()
      val renderers = mutableListOf<RecordingMlnFfiMapRenderer>()
      var showingReplacement by mutableStateOf(false)

      setContent {
        key(showingReplacement) {
          val renderer = remember { RecordingMlnFfiMapRenderer().also { renderers += it } }
          val hostResult = remember { factory.create(factory.bridges.single()) }
          MlnFfiMapSurface(renderer, hostResult, Modifier.size(64.dp))
        }
      }
      waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
        factory.created.size == 1 && factory.created[0].drawRecords.isNotEmpty()
      }
      val firstHost = factory.created.single()
      val firstRenderer = renderers.single()
      assertTrue(firstHost.acquireCount >= 1)
      assertEquals(listOf("onSurfaceAvailable"), firstRenderer.lifecycle)
      assertFalse(firstHost.closed)

      showingReplacement = true
      waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
        firstHost.closed && factory.created.size == 2 && factory.created[1].drawRecords.isNotEmpty()
      }
      waitForIdle()

      val secondHost = factory.created[1]
      val secondRenderer = renderers[1]
      assertNotSame(firstHost, secondHost)
      assertTrue(firstHost.closed)
      assertEquals(
        listOf("onSurfaceAvailable", "onSurfaceLost"),
        firstRenderer.lifecycle,
      )
      assertTrue(firstHost.leakedFrames.isEmpty())
      assertTrue(secondHost.acquireCount >= 1)
      assertTrue(secondHost.drawRecords.isNotEmpty())
      assertFalse(secondHost.closed)
      assertEquals(listOf("onSurfaceAvailable"), secondRenderer.lifecycle)
    }

  @Test
  fun a_host_receives_its_surface_after_reusable_content_is_reactivated() = runFfiComposeUiTest {
    val factory = FakeMlnFfiMapHostFactory()
    val renderers = mutableListOf<RecordingMlnFfiMapRenderer>()
    var surfaceActive by mutableStateOf(true)

    setContent {
      ReusableContentHost(active = surfaceActive) {
        val renderer = remember { RecordingMlnFfiMapRenderer().also { renderers += it } }
        val hostResult = remember { factory.create(factory.bridges.single()) }
        MlnFfiMapSurface(renderer, hostResult, Modifier.size(64.dp))
      }
    }
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
      factory.created.size == 1 && factory.created[0].drawRecords.isNotEmpty()
    }
    val firstHost = factory.created.single()
    val firstRenderer = renderers.single()
    assertTrue(firstHost.acquireCount >= 1)
    assertEquals(listOf("onSurfaceAvailable"), firstRenderer.lifecycle)
    assertFalse(firstHost.closed)

    surfaceActive = false
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) { firstHost.closed }
    waitForIdle()
    assertEquals(listOf("onSurfaceAvailable", "onSurfaceLost"), firstRenderer.lifecycle)
    assertTrue(firstHost.leakedFrames.isEmpty())

    surfaceActive = true
    waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
      factory.created.size == 2 && factory.created[1].drawRecords.isNotEmpty()
    }
    waitForIdle()

    val secondHost = factory.created[1]
    val secondRenderer = renderers[1]
    assertNotSame(firstHost, secondHost)
    assertTrue(firstHost.closed)
    assertTrue(secondHost.acquireCount >= 1)
    assertTrue(secondHost.drawRecords.isNotEmpty())
    assertFalse(secondHost.closed)
    assertEquals(listOf("onSurfaceAvailable"), secondRenderer.lifecycle)
  }

  private companion object {
    const val TIMEOUT_MILLIS = 10_000L
  }
}
