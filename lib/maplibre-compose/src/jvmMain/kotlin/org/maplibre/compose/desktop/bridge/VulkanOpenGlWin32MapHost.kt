package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.MlnFfiMapFrameProduction
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

  // Pipelined triple buffer. MapLibre renders back-to-back on the renderer thread — up to
  // [MAX_IN_FLIGHT_RENDERS] queued — while Compose presents [displayedGeneration], the newest
  // completed texture. The slow native render therefore never blocks the caller (the window's
  // only event-loop thread on Tao) and the map's content rate matches native throughput.
  private val textures = linkedMapOf<Long, WindowsOpenGlSharedTexture>()
  private val retiredTextures = mutableMapOf<Long, WindowsOpenGlSharedTexture>()
  private var generation = 0L
  private var currentExtent = MapExtent.Empty
  private val pipeline =
    AsyncFramePipeline(
      dispatch = rendererThread::post,
      releaseFrame = ::releaseFrame,
      maxPending = MAX_IN_FLIGHT_RENDERS,
    )

  private companion object {
    /** One on screen, one completing, one free to start on — the minimum that never stalls. */
    const val TEXTURE_SLOTS = 3

    /** Renders queued on the renderer thread; two keeps it busy back-to-back. */
    const val MAX_IN_FLIGHT_RENDERS = 2
  }

  override val backends: RenderBackendPair =
    RenderBackendPair(MapRenderBackend.VULKAN, ComposeRenderBackend.OPENGL)

  override fun acquireFrame(
    frameId: Long,
    extent: MapExtent,
    presentationTimeNanos: Long?,
  ): MlnFfiMapFrameAcquisition =
    gpuHost.withOpenGlContextOrNull { context ->
      frameCompletion.prepare(context.skiaContext, ::abandonContext)
      if (textures.isEmpty() || extent != currentExtent) recreateTextures(extent)
      val targetGeneration =
        requireNotNull(pipeline.acquisitionGeneration()) {
          "Windows OpenGL texture is not initialized"
        }
      MlnFfiMapFrameAcquisition.Acquired(
        MlnFfiMapFrame(
          frameId = frameId,
          extent = extent,
          target =
            requireNotNull(textures[targetGeneration]) {
                "Windows OpenGL texture is not initialized"
              }
              .exported
              .target(targetGeneration),
          presentationTimeNanos = presentationTimeNanos,
        )
      )
    } ?: MlnFfiMapFrameAcquisition.NotReady

  override fun produceFrame(
    frame: MlnFfiMapFrame,
    requestFrame: () -> Unit,
    producerRequested: Boolean,
    action: () -> MlnFfiFrameResult,
  ): MlnFfiMapFrameProduction {
    var submitted = false
    try {
      val completed = pipeline.collectCompleted()
      if (producerRequested || completed?.shouldSubmitSuccessor == true) {
        submitted =
          pipeline.submit(
            frame,
            action = {
              action().also { result ->
                if (result == MlnFfiFrameResult.RENDERED) vulkan?.waitIdle()
              }
            },
            requestFrame = requestFrame,
          )
      }
      return completed?.production ?: MlnFfiMapFrameProduction.Pending
    } finally {
      if (!submitted) releaseFrame(frame)
    }
  }

  override fun releaseFrame(frame: MlnFfiMapFrame) {
    // Texture allocations belong to their generation rather than to an individual frame token.
  }

  override fun <T> withRendererAccess(action: () -> T): T = rendererThread.run(action)

  override fun enqueueRenderer(action: () -> Unit): Boolean = rendererThread.post(action)

  override fun draw(scope: DrawScope, target: MlnFfiRenderTarget): Boolean {
    if (target !is VulkanImageTarget) return false
    return gpuHost.withOpenGlContextOrNull { context ->
      frameCompletion.prepare(context.skiaContext, ::abandonContext)
      // The loop replays the last RENDERED frame's target, but with pipelined rendering the
      // texture to present is the newest completed one; the loop's token only marks that a
      // frame has been rendered at all.
      val presentedGeneration =
        if (
          pipeline.displayedGeneration in textures ||
            pipeline.displayedGeneration in retiredTextures
        ) {
          checkNotNull(pipeline.displayedGeneration)
        } else {
          target.generation
        }
      val sharedTexture = textures[presentedGeneration] ?: retiredTextures[presentedGeneration]
      val imported = sharedTexture?.imported ?: return@withOpenGlContextOrNull false
      // Context replacement abandons GL names. Presenting texture 0 builds an
      // incomplete FBO; the next acquireFrame reallocates in the new context.
      if (imported.textureName == 0) return@withOpenGlContextOrNull false
      val drew =
        presenter.draw(
          scope,
          context.skiaContext,
          imported.target(presentedGeneration),
          frameCompletion,
        )
      // An in-flight render may still write a just-retired texture; defer disposal.
      if (drew && !pipeline.hasPending) {
        disposeRetiredTextures(exceptGeneration = presentedGeneration)
      }
      drew
    } ?: false
  }

  override fun close() {
    // The Vulkan device must outlive any in-flight render.
    runCatching { pipeline.close() }
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

  private fun recreateTextures(extent: MapExtent) {
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
    val created = ArrayList<WindowsOpenGlSharedTexture>(TEXTURE_SLOTS)
    try {
      repeat(TEXTURE_SLOTS) { created += createTexture(context, angleDevice, extent) }
    } catch (error: RuntimeException) {
      created.forEach { runCatching(it::close) }
      throw error
    }
    retireCurrentTextures()
    created.forEach { slot ->
      generation += 1
      textures[generation] = slot
    }
    pipeline.replaceActiveGenerations(textures.keys)
    currentExtent = extent
  }

  private fun createTexture(
    context: WindowsOpenGlVulkanContext,
    angleDevice: Long,
    extent: MapExtent,
  ): WindowsOpenGlSharedTexture {
    val d3d11 = WindowsD3D11Interop.createSharedTextureOnDevice(angleDevice, extent)
    try {
      val exported = rendererThread.run { context.importD3D11Texture(d3d11.sharedHandle, extent) }
      try {
        return WindowsOpenGlSharedTexture(
          d3d11,
          exported,
          WindowsOpenGlImportedTexture.bindAngle(d3d11.texture, extent),
        )
      } catch (error: RuntimeException) {
        exported.close()
        throw error
      }
    } catch (error: RuntimeException) {
      d3d11.close()
      throw error
    }
  }

  private fun retireCurrentTextures() {
    retiredTextures.putAll(textures)
    textures.clear()
    pipeline.replaceActiveGenerations(emptyList())
  }

  private fun abandonContext() {
    presenter.abandon()
    retireCurrentTextures()
    pipeline.abandonDisplayedGeneration()
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
    retireCurrentTextures()
    disposeRetiredTextures()
  }

  private fun closeAllTexturesForShutdown() {
    retireCurrentTextures()
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
