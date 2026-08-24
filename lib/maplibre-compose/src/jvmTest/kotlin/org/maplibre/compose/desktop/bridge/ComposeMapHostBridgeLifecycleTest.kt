package org.maplibre.compose.desktop.bridge

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiMapHostResult
import org.maplibre.compose.mlnffi.RenderBackendPair

class ComposeMapHostBridgeLifecycleTest {

  @Test
  fun metal_resize_waits_for_the_first_gpu_context() {
    MetalMapHost(ContextlessMapHost(ComposeRenderBackend.METAL)).use { host -> host.resize(EXTENT) }
  }

  @Test
  fun direct3d_resize_waits_for_the_first_gpu_context() {
    VulkanDirect3D12MapHost(ContextlessMapHost(ComposeRenderBackend.DIRECT3D12)).use { host ->
      host.resize(EXTENT)
    }
  }

  @Test
  fun opengl_direct3d_resize_waits_for_the_first_gpu_context() {
    OpenGlDirect3D12MapHost(ContextlessMapHost(ComposeRenderBackend.DIRECT3D12)).use { host ->
      host.resize(EXTENT)
    }
  }

  @Test
  fun direct3d_factory_prefers_vulkan_then_offers_opengl() {
    val factory = ComposeMapHostFactory(ContextlessMapHost(ComposeRenderBackend.DIRECT3D12))

    assertEquals(
      listOf(
        RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.DIRECT3D12),
        RenderBackendPair(MapRenderBackend.OPENGL, ComposeRenderBackend.DIRECT3D12),
      ),
      factory.bridges,
    )
  }

  @Test
  fun unsupported_factory_pair_names_the_host_and_backends() {
    val factory = ComposeMapHostFactory(ContextlessMapHost(ComposeRenderBackend.DIRECT3D12))

    val result =
      assertIs<MlnFfiMapHostResult.Failed>(
        factory.create(RenderBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.DIRECT3D12))
      )

    assertContains(result.diagnostic, "contextless test host")
    assertContains(result.diagnostic, "METAL")
    assertContains(result.diagnostic, "DIRECT3D12")
  }

  private class ContextlessMapHost(override val backend: ComposeRenderBackend) : ComposeMapHost {
    override val description: String = "contextless test host"

    override fun gpuContext(): ComposeGpuContext? = null

    override fun runOnGpuThread(action: Runnable) {
      action.run()
    }
  }

  private companion object {
    val EXTENT = MapExtent.fromPhysical(physicalWidth = 64, physicalHeight = 64, scaleFactor = 1.0)
  }
}
