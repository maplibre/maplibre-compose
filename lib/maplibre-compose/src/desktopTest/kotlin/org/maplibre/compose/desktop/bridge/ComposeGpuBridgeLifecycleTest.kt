package org.maplibre.compose.desktop.bridge

import kotlin.test.Test
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeGpuHost
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiMapExtent

class ComposeGpuBridgeLifecycleTest {

  @Test
  fun metal_resize_waits_for_the_first_gpu_context() {
    MetalMapHost(ContextlessGpuHost(ComposeRenderBackend.METAL)).use { host -> host.resize(EXTENT) }
  }

  @Test
  fun direct3d_resize_waits_for_the_first_gpu_context() {
    VulkanDirect3D12MapHost(ContextlessGpuHost(ComposeRenderBackend.DIRECT3D12)).use { host ->
      host.resize(EXTENT)
    }
  }

  private class ContextlessGpuHost(override val backend: ComposeRenderBackend) : ComposeGpuHost {
    override val description: String = "contextless test host"

    override fun gpuContext(): ComposeGpuContext? = null

    override fun runOnGpuThread(action: Runnable) {
      action.run()
    }
  }

  private companion object {
    val EXTENT =
      MlnFfiMapExtent.fromPhysical(physicalWidth = 64, physicalHeight = 64, scaleFactor = 1.0)
  }
}
