@file:OptIn(org.maplibre.compose.util.ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.maplibre.compose.desktop.AngleD3D11PresentationHost
import org.maplibre.compose.desktop.OpenGlComposeGpuContext
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.TextureOrigin

/**
 * Bridges MapLibre's Vulkan or OpenGL rendering into Compose's ANGLE/GLES context on Windows.
 *
 * MapLibre draws into a D3D11 texture created on ANGLE's device. The producer imports the NT
 * handle; Compose samples the same texture via `EGL_ANGLE_d3d_texture_client_buffer`.
 */
internal class WindowsAngleMapHost(
  presentationHost: AngleD3D11PresentationHost,
  producer: MapRenderBackend = MapRenderBackend.Vulkan,
) :
  SharedTextureMapHost<OpenGlComposeGpuContext, WindowsAngleMapHost.WindowsOpenGlSharedTexture>(
    presentationHost,
    RenderBackendPair(producer, ComposeRenderBackend.OpenGl),
    "maplibre-windows-vulkan-gl-renderer",
  ) {
  private val presenter = SkiaTexturePresenter(OpenGlTextureWrapper.Angle)
  private val adapterChange =
    DeviceChangeRecovery<Long>("ANGLE moved to another graphics adapter; rebuilding the map bridge")
  private var vulkan: VulkanDevice? = null
  private var wgl: WindowsWglContext? = null
  private var producerAdapterLuid = 0L

  override fun acquireFrame(extent: MapExtent): MlnFfiMapFrameAcquisition =
    withPreparedContext {
      if (textures.current?.extent != extent) recreateTexture(extent)
      MlnFfiMapFrameAcquisition.Acquired(
        MlnFfiMapFrame(
          target =
            requireNotNull(textures.current) { "Windows OpenGL texture is not initialized" }
              .exported
              .target(textures.generation)
        )
      )
    } ?: MlnFfiMapFrameAcquisition.NotReady

  override fun waitForProducers() {
    vulkan?.waitIdle()
    wgl?.waitIdle()
  }

  override fun present(
    scope: DrawScope,
    context: OpenGlComposeGpuContext,
    texture: WindowsOpenGlSharedTexture,
    generation: Long,
    destination: MlnFfiMapDestination,
  ): Boolean {
    val imported = texture.imported
    // Context replacement abandons GL names. Presenting texture 0 builds an
    // incomplete FBO; the next acquireFrame reallocates in the new context.
    if (imported.textureName == 0) return false
    return presenter.draw(
      scope,
      context.skiaContext,
      imported
        .target(generation)
        .copy(
          origin =
            if (producer == MapRenderBackend.OpenGl) TextureOrigin.BottomLeft
            else TextureOrigin.TopLeft
        ),
      destination,
      frameCompletion,
    )
  }

  override fun release(texture: WindowsOpenGlSharedTexture) {
    texture.close()
  }

  override fun contextReplaced() {
    presenter.abandonAll()
    textures.retireCurrent()
    textures.all.forEach(WindowsOpenGlSharedTexture::abandonImported)
  }

  override fun closeTextures() {
    val closing = textures.removeAll()
    val closedWithContext = runCatching {
      checkNotNull(
        presentationHost.withContext {
          closing.forEach(WindowsOpenGlSharedTexture::closeImported)
          presenter.closeAll()
        }
      )
    }
      .isSuccess
    if (!closedWithContext) {
      presenter.abandonAll()
      closing.forEach(WindowsOpenGlSharedTexture::abandonImported)
    }
    closing.forEach { runCatching(it::closeInterop) }
  }

  override fun closeProducers() {
    val closing = vulkan
    vulkan = null
    producerAdapterLuid = 0L
    closing?.close()
    wgl?.close()
    wgl = null
  }

  private fun recreateTexture(extent: MapExtent) {
    if (extent.isEmpty) {
      textures.releaseAll()
      textures.replaceCurrent(null)
      return
    }

    val angleDevice = AngleEgl.angleD3d11Device()
    val adapterLuid = WindowsD3D11Interop.adapterLuidOf(angleDevice)
    check(adapterLuid != 0L) {
      "ANGLE's ID3D11Device has no DXGI adapter LUID; cannot pick a matching producer device"
    }
    val producerLuid = producerAdapterLuid.takeIf { vulkan != null || wgl != null }
    if (adapterChange.changed(producerLuid, adapterLuid)) {
      textures.releaseAll()
      rendererThread.run(::closeProducers)
    }
    val d3d11 = WindowsD3D11Interop.createSharedTextureOnDevice(angleDevice, extent)
    try {
      val exported = rendererThread.run {
        val imported =
          if (producer == MapRenderBackend.OpenGl) {
            val context = wgl ?: WindowsWglContext.create().also { wgl = it }
            context.importTexture(d3d11.sharedHandle, extent, "ANGLE", adapterLuid, d3d11 = true)
          } else {
            val context = vulkan ?: VulkanDevice.forAdapter(adapterLuid).also { vulkan = it }
            context.importD3D11Texture(d3d11.sharedHandle, extent)
          }
        producerAdapterLuid = adapterLuid
        imported
      }
      try {
        val imported = WindowsOpenGlImportedTexture.bindAngle(d3d11.texture, extent)
        textures.replaceCurrent(WindowsOpenGlSharedTexture(extent, d3d11, exported, imported))
      } catch (error: RuntimeException) {
        rendererThread.run { exported.close() }
        throw error
      }
    } catch (error: RuntimeException) {
      d3d11.close()
      throw error
    }
  }

  internal inner class WindowsOpenGlSharedTexture(
    val extent: MapExtent,
    val d3d11: WindowsD3D11SharedTexture,
    val exported: ImportedMapTexture,
    val imported: WindowsOpenGlImportedTexture,
  ) : AutoCloseable {
    private var interopClosed = false

    override fun close() {
      try {
        closeImported()
      } finally {
        closeInterop()
      }
    }

    fun closeImported() {
      presenter.forget(imported.textureName.toLong())
      imported.close()
    }

    fun abandonImported() {
      imported.abandon()
    }

    fun closeInterop() {
      if (interopClosed) return
      interopClosed = true
      try {
        rendererThread.run { exported.close() }
      } finally {
        d3d11.close()
      }
    }
  }
}
