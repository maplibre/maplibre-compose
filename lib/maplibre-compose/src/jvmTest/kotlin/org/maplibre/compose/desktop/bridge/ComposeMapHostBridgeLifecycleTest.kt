package org.maplibre.compose.desktop.bridge

import kotlin.test.Test
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend

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
  fun windows_opengl_resize_waits_for_the_first_gpu_context() {
    VulkanOpenGlWin32MapHost(ContextlessMapHost(ComposeRenderBackend.OPENGL)).use { host ->
      host.resize(EXTENT)
    }
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
