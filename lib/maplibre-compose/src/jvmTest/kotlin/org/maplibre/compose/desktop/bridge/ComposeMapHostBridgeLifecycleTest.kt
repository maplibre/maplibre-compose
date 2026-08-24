package org.maplibre.compose.desktop.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.desktop.OpenGlInterop
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiHostException

class ComposeMapHostBridgeLifecycleTest {

  @Test
  fun native_opengl_selects_the_native_bridge_on_every_platform() {
    assertEquals(OpenGlBridge.NATIVE, selectOpenGlBridge(OpenGlInterop.NATIVE, windows = false))
    assertEquals(OpenGlBridge.NATIVE, selectOpenGlBridge(OpenGlInterop.NATIVE, windows = true))
  }

  @Test
  fun angle_d3d11_selects_the_windows_bridge() {
    assertEquals(
      OpenGlBridge.ANGLE_D3D11,
      selectOpenGlBridge(OpenGlInterop.ANGLE_D3D11, windows = true),
    )
  }

  @Test
  fun angle_d3d11_reports_an_unsupported_platform() {
    assertFailsWith<MlnFfiHostException> {
      selectOpenGlBridge(OpenGlInterop.ANGLE_D3D11, windows = false)
    }
  }

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
