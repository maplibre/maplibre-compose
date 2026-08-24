package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.MlnFfiMapHost
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.VulkanImageTarget

/**
 * Bridges MapLibre's Vulkan rendering into Compose's ANGLE/GLES context on Windows.
 *
 * MapLibre draws into a D3D11 texture created on ANGLE's device. Vulkan imports the NT handle;
 * Compose samples the same texture via `EGL_ANGLE_d3d_texture_client_buffer`.
 */
internal class VulkanOpenGlWin32MapHost(private val gpuHost: ComposeMapHost) : MlnFfiMapHost {
  private val rendererThread = MapRendererThread("maplibre-windows-vulkan-gl-renderer")
  private val presenter = OpenGlPresenter.angle()
  private val frameCompletion = ComposeFrameCompletion()
  private var vulkan: WindowsOpenGlVulkanContext? = null
  private var texture: WindowsOpenGlSharedTexture? = null
  private val retiredTextures = mutableMapOf<Long, WindowsOpenGlSharedTexture>()
  private var generation = 0L
  private var currentExtent = MapExtent.Empty

  override val backends: RenderBackendPair =
    RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)

  override fun acquireFrame(
    frameId: Long,
    extent: MapExtent,
    presentationTimeNanos: Long?,
  ): MlnFfiMapFrameAcquisition =
    gpuHost.withOpenGlContextOrNull { context ->
      frameCompletion.prepare(context.skiaContext, ::abandonContext)
      if (texture == null || extent != currentExtent) recreateTexture(extent)
      MlnFfiMapFrameAcquisition.Acquired(
        MlnFfiMapFrame(
          frameId = frameId,
          extent = extent,
          target =
            requireNotNull(texture) { "Windows OpenGL texture is not initialized" }
              .exported
              .target(generation),
          presentationTimeNanos = presentationTimeNanos,
        )
      )
    } ?: MlnFfiMapFrameAcquisition.NotReady

  override fun completeProducerAccess(frame: MlnFfiMapFrame) {
    rendererThread.run { vulkan?.waitIdle() }
  }

  override fun <T> withProducerAccess(frame: MlnFfiMapFrame, action: () -> T): T =
    rendererThread.run(action)

  override fun <T> withRendererAccess(action: () -> T): T = rendererThread.run(action)

  override fun enqueueRenderer(action: () -> Unit): Boolean = rendererThread.post(action)

  override fun draw(scope: DrawScope, target: MlnFfiRenderTarget): Boolean {
    if (target !is VulkanImageTarget) return false
    return gpuHost.withOpenGlContextOrNull { context ->
      frameCompletion.prepare(context.skiaContext, ::abandonContext)
      val sharedTexture =
        if (target.generation == generation) texture else retiredTextures[target.generation]
      val imported = sharedTexture?.imported ?: return@withOpenGlContextOrNull false
      // Context replacement abandons GL names. Presenting texture 0 builds an
      // incomplete FBO; the next acquireFrame reallocates in the new context.
      if (imported.textureName == 0) return@withOpenGlContextOrNull false
      val drew =
        presenter.draw(
          scope,
          context.skiaContext,
          imported.target(target.generation),
          frameCompletion,
        )
      if (drew) disposeRetiredTextures(exceptGeneration = target.generation)
      drew
    } ?: false
  }

  override fun close() {
    try {
      frameCompletion.abandon()
      closeAllTexturesForShutdown()
    } finally {
      val closing = vulkan
      vulkan = null
      try {
        closing?.close()
      } finally {
        rendererThread.close()
      }
    }
  }

  private fun recreateTexture(extent: MapExtent) {
    if (extent.isEmpty) {
      disposeAllTextures()
      currentExtent = MapExtent.Empty
      generation += 1
      return
    }

    val angleDevice = AngleEgl.angleD3d11Device()
    val adapterLuid = WindowsD3D11Interop.adapterLuidOf(angleDevice)
    check(adapterLuid != 0L) {
      "ANGLE's ID3D11Device has no DXGI adapter LUID; cannot pick a matching Vulkan device"
    }
    val context =
      vulkan
        ?: rendererThread
          .run { WindowsOpenGlVulkanContext.create(adapterLuid) }
          .also { vulkan = it }
    val d3d11 = WindowsD3D11Interop.createSharedTextureOnDevice(angleDevice, extent)
    try {
      val exported = rendererThread.run { context.importD3D11Texture(d3d11.sharedHandle, extent) }
      try {
        val imported = WindowsOpenGlImportedTexture.bindAngle(d3d11.texture, extent)
        texture?.let { retiredTextures[generation] = it }
        texture = WindowsOpenGlSharedTexture(d3d11, exported, imported)
        currentExtent = extent
        generation += 1
      } catch (error: RuntimeException) {
        exported.close()
        throw error
      }
    } catch (error: RuntimeException) {
      d3d11.close()
      throw error
    }
  }

  private fun retireCurrentTexture() {
    texture?.let { retiredTextures[generation] = it }
    texture = null
  }

  private fun abandonContext() {
    presenter.abandon()
    retireCurrentTexture()
    retiredTextures.values.forEach(WindowsOpenGlSharedTexture::abandonImported)
    currentExtent = MapExtent.Empty
  }

  private fun disposeRetiredTextures(exceptGeneration: Long? = null) {
    val iterator = retiredTextures.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (entry.key != exceptGeneration) {
        entry.value.close()
        iterator.remove()
      }
    }
  }

  private fun disposeAllTextures() {
    texture?.close()
    texture = null
    disposeRetiredTextures()
  }

  private fun closeAllTexturesForShutdown() {
    retireCurrentTexture()
    val closing = retiredTextures.values.toList()
    val closedWithContext = runCatching {
      gpuHost.withOpenGlContext {
        closing.forEach(WindowsOpenGlSharedTexture::closeImported)
        presenter.close()
      }
    }
      .isSuccess
    if (!closedWithContext) {
      presenter.abandon()
      closing.forEach(WindowsOpenGlSharedTexture::abandonImported)
    }
    closing.forEach { runCatching(it::closeInterop) }
    retiredTextures.clear()
  }

  private inner class WindowsOpenGlSharedTexture(
    val d3d11: WindowsD3D11SharedTexture,
    val exported: WindowsOpenGlExportedVulkanTexture,
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
      presenter.forget(imported.textureName)
      imported.close()
    }

    fun abandonImported() {
      imported.abandon()
    }

    fun closeInterop() {
      if (interopClosed) return
      interopClosed = true
      try {
        exported.close()
      } finally {
        d3d11.close()
      }
    }
  }
}
