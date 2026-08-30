package org.maplibre.compose.desktop.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.ComposeMapPresentationHost
import org.maplibre.compose.desktop.OpenGlInterop
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiHostException

class ComposeMapPresentationHostBridgeLifecycleTest {

  @Test
  fun native_opengl_selects_the_linux_bridge() {
    assertEquals(
      OpenGlBridge.NATIVE,
      selectOpenGlBridge(OpenGlInterop.NATIVE, windows = false, linux = true),
    )
  }

  @Test
  fun native_opengl_reports_an_unsupported_platform() {
    assertFailsWith<MlnFfiHostException> {
      selectOpenGlBridge(OpenGlInterop.NATIVE, windows = true, linux = false)
    }
  }

  @Test
  fun angle_d3d11_selects_the_windows_bridge() {
    assertEquals(
      OpenGlBridge.ANGLE_D3D11,
      selectOpenGlBridge(OpenGlInterop.ANGLE_D3D11, windows = true, linux = false),
    )
  }

  @Test
  fun angle_d3d11_reports_an_unsupported_platform() {
    assertFailsWith<MlnFfiHostException> {
      selectOpenGlBridge(OpenGlInterop.ANGLE_D3D11, windows = false, linux = true)
    }
  }

  @Test
  fun metal_resize_waits_for_the_first_gpu_context() {
    MetalMapHost(ContextlessPresentationHost(ComposeRenderBackend.METAL)).use { host ->
      host.resize(EXTENT)
    }
  }

  @Test
  fun direct3d_resize_waits_for_the_first_gpu_context() {
    VulkanDirect3D12MapHost(ContextlessPresentationHost(ComposeRenderBackend.DIRECT3D12)).use { host
      ->
      host.resize(EXTENT)
    }
  }

  @Test
  fun windows_opengl_resize_waits_for_the_first_gpu_context() {
    VulkanOpenGlWin32MapHost(ContextlessPresentationHost(ComposeRenderBackend.OPENGL)).use { host ->
      host.resize(EXTENT)
    }
  }

  private class ContextlessPresentationHost(override val backend: ComposeRenderBackend) :
    ComposeMapPresentationHost {
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
