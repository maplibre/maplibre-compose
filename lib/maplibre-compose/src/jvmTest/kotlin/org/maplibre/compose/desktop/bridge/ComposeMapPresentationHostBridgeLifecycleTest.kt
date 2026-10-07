package org.maplibre.compose.desktop.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.maplibre.compose.desktop.AngleD3D11PresentationHost
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeMapPresentationHost
import org.maplibre.compose.desktop.DesktopComposeMapPresentationHost
import org.maplibre.compose.desktop.Direct3D12ComposeGpuContext
import org.maplibre.compose.desktop.Direct3D12PresentationHost
import org.maplibre.compose.desktop.MetalComposeGpuContext
import org.maplibre.compose.desktop.MetalPresentationHost
import org.maplibre.compose.desktop.OpenGlComposeGpuContext
import org.maplibre.compose.desktop.OpenGlPresentationHost
import org.maplibre.compose.desktop.checkOperatingSystem
import org.maplibre.compose.desktop.skiko.HostOperatingSystem
import org.maplibre.compose.location.XdgPortalWindow
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.MlnFfiMapHostResult
import org.maplibre.compose.mlnffi.ProductionBridgeTestRenderDriver
import org.maplibre.compose.mlnffi.RenderBackendPair

class ComposeMapPresentationHostBridgeLifecycleTest {
  @Test
  fun capabilities_are_available_before_gpu_initialization_and_keep_producer_preference() {
    val access = GpuAccess()
    for (host in contextlessHosts(access)) {
      val producers =
        if (host is MetalPresentationHost) {
          listOf(MapRenderBackend.Metal, MapRenderBackend.Vulkan, MapRenderBackend.OpenGl)
        } else {
          listOf(MapRenderBackend.Vulkan, MapRenderBackend.OpenGl)
        }
      assertEquals(producers, host.bridges.map { it.producer })
    }
    assertEquals(0, access.contextReads)
    assertEquals(0, access.actions)
  }

  @Test
  fun factory_does_not_create_a_bridge_to_a_different_consumer() {
    assertIs<MlnFfiMapHostResult.Failed>(
      MetalPresentationHost("test", { null }, { it.run() })
        .create(RenderBackendPair(MapRenderBackend.Vulkan, ComposeRenderBackend.Direct3D12))
    )
    assertIs<MlnFfiMapHostResult.Failed>(
      Direct3D12PresentationHost("test", { null }, { it.run() })
        .create(RenderBackendPair(MapRenderBackend.Metal, ComposeRenderBackend.Direct3D12))
    )
  }

  @Test
  fun context_is_read_again_under_gpu_access_after_initialization_and_replacement() {
    ProductionBridgeTestRenderDriver.create().use { driver ->
      driver.withComposeContext { borrowed ->
        when (borrowed) {
          is MetalComposeGpuContext ->
            assertContextReplacement(
              borrowed,
              MetalComposeGpuContext(borrowed.skiaContext, borrowed.device),
            ) { getter, access ->
              MetalPresentationHost("test", getter, access)
            }
          is Direct3D12ComposeGpuContext ->
            assertContextReplacement(
              borrowed,
              Direct3D12ComposeGpuContext(borrowed.skiaContext, borrowed.device),
            ) { getter, access ->
              Direct3D12PresentationHost("test", getter, access)
            }
          is OpenGlComposeGpuContext ->
            assertContextReplacement(
              borrowed,
              OpenGlComposeGpuContext(borrowed.skiaContext, borrowed.withContextCurrent),
            ) { getter, access ->
              OpenGlPresentationHost("test", getter, access)
            }
        }
      }
    }
  }

  private fun <C : ComposeGpuContext> assertContextReplacement(
    first: C,
    next: C,
    factory: (() -> C?, (Runnable) -> Unit) -> DesktopComposeMapPresentationHost<C>,
  ) {
    val access = GpuAccess()
    var context: C? = null
    val host = factory({ access.read(context) }, access::run)
    assertEquals(null, host.withContext { error("No context yet") })
    context = first
    assertSame(first, host.withContext { it })
    context = next
    assertSame(next, host.withContext { it })
    context = null
    assertEquals(null, host.withContext { error("Context was removed") })
    assertEquals(4, access.contextReads)
  }

  @Test
  fun nested_gpu_access_is_synchronous_and_propagates_action_failures() {
    val access = GpuAccess()
    val host =
      MetalPresentationHost("test", { access.read<MetalComposeGpuContext>(null) }, access::run)
    val failure = IllegalStateException("test failure")
    assertSame(
      failure,
      assertFailsWith<IllegalStateException> {
        host.onGpuThread { host.onGpuThread { throw failure } }
      },
    )
    assertEquals(2, access.actions)
    assertTrue(!access.hasGpuAccess)
    assertEquals(null, host.currentContext())
  }

  @Test
  fun missing_synchronous_gpu_action_is_reported() {
    val host = MetalPresentationHost("test", { null }, {})
    assertFailsWith<IllegalStateException> { host.currentContext() }
  }

  @Test
  fun portal_window_is_read_lazily_and_can_become_available() {
    var reads = 0
    var window: XdgPortalWindow? = null
    val host =
      OpenGlPresentationHost(
        "test",
        { null },
        { it.run() },
        {
          reads++
          window
        },
      )
    assertEquals(0, reads)
    assertEquals(null, host.xdgPortalWindow)
    window = XdgPortalWindow.X11(123)
    assertSame(window, host.xdgPortalWindow)
    assertEquals(2, reads)
  }

  @Test
  fun factories_reject_other_operating_systems() {
    checkOperatingSystem("linuxOpenGl", HostOperatingSystem.Linux, osName = "Linux")
    val failure =
      assertFailsWith<IllegalStateException> {
        checkOperatingSystem("windowsAngle", HostOperatingSystem.Windows, osName = "Mac OS X")
      }
    assertEquals(
      "ComposeMapPresentationHost.windowsAngle requires Windows, but this is 'Mac OS X'",
      failure.message,
    )

    val factories =
      listOf(
        HostOperatingSystem.Macos to
          {
            ComposeMapPresentationHost.macosMetal("test", { null }, { it.run() })
          },
        HostOperatingSystem.Windows to
          {
            ComposeMapPresentationHost.windowsDirect3d12("test", { null }, { it.run() })
          },
        HostOperatingSystem.Linux to
          {
            ComposeMapPresentationHost.linuxOpenGl("test", { null }, { it.run() })
          },
        HostOperatingSystem.Windows to
          {
            ComposeMapPresentationHost.windowsAngle("test", { null }, { it.run() })
          },
      )
    for ((required, create) in factories) {
      if (required == HostOperatingSystem.current()) {
        create()
      } else {
        assertFailsWith<IllegalStateException> { create() }
      }
    }
  }

  @Test
  fun maps_get_separate_bridges_and_wait_for_a_context() {
    val access = GpuAccess()
    for (windowHost in contextlessHosts(access)) {
      for (backends in windowHost.bridges) {
        val first = assertIs<MlnFfiMapHostResult.Created>(windowHost.create(backends)).host
        first.use {
          val second = assertIs<MlnFfiMapHostResult.Created>(windowHost.create(backends)).host
          second.use {
            assertNotSame(first, second)

            assertEquals(MlnFfiMapFrameAcquisition.NotReady, first.acquireFrame(Extent))
            assertEquals(MlnFfiMapFrameAcquisition.NotReady, second.acquireFrame(Extent))
          }
        }
      }
    }
    assertTrue(access.contextReads > 0)
    assertTrue(access.actions > 0)
    assertTrue(!access.hasGpuAccess)
  }

  private fun contextlessHosts(access: GpuAccess): List<DesktopComposeMapPresentationHost<*>> =
    listOf(
      MetalPresentationHost("test", { access.read(null) }, access::run),
      Direct3D12PresentationHost("test", { access.read(null) }, access::run),
      OpenGlPresentationHost("test", { access.read(null) }, access::run),
      AngleD3D11PresentationHost("test", { access.read(null) }, access::run),
    )

  private class GpuAccess {
    var contextReads = 0
      private set

    var actions = 0
      private set

    var hasGpuAccess = false
      private set

    fun <C> read(context: C?): C? {
      check(hasGpuAccess) { "Context must be read under GPU access" }
      contextReads++
      return context
    }

    fun run(action: Runnable) {
      actions++
      val previous = hasGpuAccess
      hasGpuAccess = true
      try {
        action.run()
      } finally {
        hasGpuAccess = previous
      }
    }
  }

  private companion object {
    val Extent = MapExtent.fromPhysical(physicalWidth = 64, physicalHeight = 64, scaleFactor = 1.0)
  }
}
