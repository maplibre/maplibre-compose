@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalSurfaceTarget
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapHostSession
import org.maplibre.compose.mlnffi.MlnFfiMapPresentationAnchor
import org.maplibre.compose.mlnffi.MlnFfiMapRenderer
import org.maplibre.compose.mlnffi.MlnFfiRecoverableFrameException
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.OpenGlContextHandles
import org.maplibre.compose.mlnffi.OpenGlSurfaceTarget
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.VulkanContextHandles
import org.maplibre.compose.mlnffi.VulkanImageTarget
import org.maplibre.compose.mlnffi.VulkanSurfaceTarget
import org.maplibre.compose.mlnffi.WglContextHandles
import org.maplibre.compose.mlnffi.currentMlnFfiThreadName
import org.maplibre.nativeffi.camera.EdgeInsets
import org.maplibre.nativeffi.error.InvalidArgumentException
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.error.NativeErrorException
import org.maplibre.nativeffi.error.UnsupportedFeatureException
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.MetalBorrowedTextureDescriptor
import org.maplibre.nativeffi.render.MetalContextDescriptor
import org.maplibre.nativeffi.render.MetalSurfaceDescriptor
import org.maplibre.nativeffi.render.NativePointer
import org.maplibre.nativeffi.render.OpenGLBorrowedTextureDescriptor
import org.maplibre.nativeffi.render.OpenGLClientApi
import org.maplibre.nativeffi.render.OpenGLContextOwnership
import org.maplibre.nativeffi.render.OpenGLSurfaceDescriptor
import org.maplibre.nativeffi.render.RenderResult
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.render.RenderTargetExtent
import org.maplibre.nativeffi.render.VulkanBorrowedTextureDescriptor
import org.maplibre.nativeffi.render.VulkanSurfaceDescriptor

/** The fraction of a capped frame interval a frame may arrive early and still be drawn. */
private const val FRAME_INTERVAL_SLACK = 0.1

/**
 * The render session over an [MlnFfiMapCore]: the host surface, the [RenderSessionHandle], and
 * frame scheduling, all on the host's renderer thread. A camera transition only steps while frames
 * are being drawn: mbgl advances it from `onDidFinishRenderingFrame`. Closing this session closes
 * only the render half; the core belongs to the [MapEngine] that owns it.
 */
internal class MlnFfiMapSession(
  internal val core: MlnFfiMapCore,
  override val backend: MapRenderBackend,
) : MlnFfiMapRenderer, MlnFfiRenderSessionAccess, GestureTarget by core {

  /**
   * The padding of the last rendered frame; the surface anchors preserved resize frames with it.
   */
  @Volatile private var renderedCameraPadding: EdgeInsets = EdgeInsets.ZERO

  /** Renderer-thread state. */
  private var renderSession: RenderSessionHandle? = null

  /** Renderer-thread state; the FFI creates its renderer during the first successful render. */
  private var renderSessionReady = false

  @Volatile private var hostSession: MlnFfiMapHostSession? = null

  private data class TargetKey(val generation: Long, val extent: MapExtent)

  private var attachedTarget: TargetKey? = null

  /** Renderer-thread state, read by tests. */
  @Volatile
  internal var attachCount: Int = 0
    private set

  @Volatile
  internal var retargetCount: Int = 0
    private set

  private val renderRequested = AtomicBoolean(true)

  private var hasRenderedAFrame = false

  @Volatile private var closed = false

  /** True after [close]; the engine and tests read it to observe an eviction. */
  internal val isClosed: Boolean
    get() = closed

  private var failureReported = false

  private var lastRenderTime = TimeSource.Monotonic.markNow()

  private val frameTimer = TimeSource.Monotonic
  private var lastFrameTime = frameTimer.markNow()

  init {
    core.attachRenderSession(this)
  }

  // region MlnFfiRenderSessionAccess

  /** Safe from any thread. */
  override fun requestRender() {
    renderRequested.store(true)
    hostSession?.requestFrame()
  }

  override fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T? {
    return withRendererAccess {
      if (!renderSessionReady) return@withRendererAccess null
      val session = renderSession
      if (session == null) {
        core.logger?.d { "Ignoring a render session call: no session is attached yet" }
        return@withRendererAccess null
      }
      action(session)
    }
  }

  override fun enqueueRenderSessionWork(work: (RenderSessionHandle?) -> Unit): Boolean {
    val host = hostSession ?: return false
    return host.enqueueRenderer { work(renderSession) }
  }

  /**
   * Never throws. The capture, the reset, and the close all run under renderer access:
   * [renderSession], [renderSessionReady], and [attachedTarget] are renderer-thread state, and
   * touching them from the arbitrary thread reaching here through [MlnFfiMapCore.close] could race
   * a concurrent retarget into a double close.
   */
  override fun closeRenderSession() {
    val result = runCatching {
      withRendererAccess {
        val handle = renderSession
        renderSession = null
        renderSessionReady = false
        attachedTarget = null
        // Close inside its own guard so a throwing handle still counts as the closure running.
        handle?.let { live ->
          runCatching { live.close() }
            .onFailure { core.logger?.e(it) { "Failed to close the MapLibre render session" } }
        }
        true
      }
    }
    result.onFailure { core.logger?.e(it) { "Could not reach the renderer to close the session" } }
    if (result.getOrNull() == true) return
    // The renderer is unreachable, so nothing races these fields; a live handle can only leak
    // because only the thread that attached it may close it.
    if (renderSession != null) {
      core.logger?.w { "Leaking a MapLibre render session: its host surface is already gone" }
    }
    renderSession = null
    renderSessionReady = false
    attachedTarget = null
  }

  // endregion

  // region host surface lifecycle

  override fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
    if (closed) {
      core.logger?.w { "Ignoring a host surface offered to a closed map session" }
      return
    }
    hostSession = session
    // An idle map publishes no render update, so a surface that returns after loss is never drawn
    // into without this.
    requestRender()
  }

  override fun onSurfaceChanged(extent: MapExtent) {
    // The map is resized as part of attaching the new target; see ensureAttached.
    requestRender()
  }

  override fun onSurfaceLost() {
    // The render session must go before the host session is dropped: that is the only route to the
    // thread allowed to close the handle.
    core.logger?.i { "Host surface lost; closing the render session and waiting for a new one" }
    closeRenderSession()
    hostSession = null
  }

  override fun render(frame: MlnFfiMapFrame): MlnFfiFrameResult {
    if (closed || frame.extent.isEmpty) return MlnFfiFrameResult.SKIPPED

    val loop = core.runtimeLoop ?: return MlnFfiFrameResult.SKIPPED
    loop.failure?.let { error ->
      if (!failureReported) {
        failureReported = true
        // The host stops driving frames after a failure, so nothing else would close the session.
        close()
        throw IllegalStateException("The MapLibre map runtime failed", error)
      }
      return MlnFfiFrameResult.SKIPPED
    }

    val map = loop.map ?: return MlnFfiFrameResult.SKIPPED
    renderedCameraPadding = core.appliedCameraPadding

    if (!ensureAttached(map, frame)) return MlnFfiFrameResult.SKIPPED
    // Consumed before rendering, so an update published during the render below is not discarded.
    if (!renderRequested.exchange(false)) return MlnFfiFrameResult.SKIPPED
    // The cap measures start-to-start; measuring from the end of the last render rejects every
    // second frame near the display's rate.
    val renderStart = TimeSource.Monotonic.markNow()
    if (!allowRenderNow(renderStart)) {
      // Throttled, not dropped.
      requestRender()
      return MlnFfiFrameResult.SKIPPED
    }

    val session = renderSession ?: return MlnFfiFrameResult.SKIPPED
    val update =
      try {
        session.renderUpdate()
      } catch (error: NativeErrorException) {
        throw MlnFfiRecoverableFrameException("The MapLibre render session failed", error)
      }
    if (update.result == RenderResult.RENDERED) {
      renderSessionReady = true
      core.replayPendingFeatureState(session)
    }
    when (update.result) {
      RenderResult.NO_UPDATE,
      RenderResult.SIZE_PENDING -> return MlnFfiFrameResult.SKIPPED
      RenderResult.TARGET_NOT_READY -> {
        requestRender()
        return MlnFfiFrameResult.SKIPPED
      }
      else -> Unit
    }
    if (update.needsRepaint) requestRender()

    if (!hasRenderedAFrame) {
      hasRenderedAFrame = true
      core.logger?.i {
        "Rendered the first map frame with $backend on ${currentMlnFfiThreadName()}, " +
          "extent ${frame.extent}"
      }
    }
    lastRenderTime = renderStart
    reportFrameRate()
    return MlnFfiFrameResult.RENDERED
  }

  override fun presentationAnchor(extent: MapExtent): MlnFfiMapPresentationAnchor {
    val padding = renderedCameraPadding
    return MlnFfiMapPresentationAnchor(
      x =
        ((extent.physicalWidth + (padding.left - padding.right) * extent.scaleFactor) / 2.0)
          .toInt(),
      y =
        ((extent.physicalHeight + (padding.top - padding.bottom) * extent.scaleFactor) / 2.0)
          .toInt(),
    )
  }

  /** Closes only the render half and detaches from the core, which survives for a later session. */
  override fun close() {
    if (closed) return
    closed = true
    try {
      closeRenderSession()
    } finally {
      hostSession = null
      core.detachRenderSession(this)
    }
  }

  // endregion

  // region render target attachment

  private fun ensureAttached(map: MapHandle, frame: MlnFfiMapFrame): Boolean {
    val extent = frame.extent
    if (extent.isEmpty) return false

    val key = TargetKey(frame.target.generation, extent)
    val attached = attachedTarget
    if (attached == key && renderSession != null) return true

    // A renderer compiles its shaders for one pixel ratio, so a scale-factor change needs a new
    // one.
    val live = renderSession
    if (live != null && attached != null && attached.extent.scaleFactor == extent.scaleFactor) {
      if (retargetBorrowedTexture(live, frame.target, extent)) {
        attachedTarget = key
        retargetCount++
        // The replacement texture holds nothing yet; this request buys the frame that fills it.
        renderRequested.store(true)
        core.postViewportSnapshot()
        return true
      }
    }

    // Attaching before closing throws, because a map permits only one live session.
    closeRenderSession()

    // There is no map.resize: attaching sets the map's size from the descriptor's logical extent.
    renderSession =
      try {
        attachBorrowedTexture(map, frame.target, extent)
      } catch (error: Throwable) {
        core.logger?.e(error) { "Failed to attach a render session to the host target" }
        throw error
      }
    renderSessionReady = false
    core.markFeatureStateReplayPending()
    attachedTarget = key
    attachCount++
    core.publishAttachedViewport()
    core.postViewportSnapshot()
    // The new texture holds nothing yet; this request buys the frame that fills it.
    renderRequested.store(true)
    return true
  }

  private fun attachBorrowedTexture(
    map: MapHandle,
    target: MlnFfiRenderTarget,
    extent: MapExtent,
  ): RenderSessionHandle =
    when (target) {
      is VulkanImageTarget -> map.attachVulkanBorrowedTexture(target.toDescriptor(extent))
      is VulkanSurfaceTarget -> map.attachVulkanSurface(target.toDescriptor(extent))
      is MetalTextureTarget -> map.attachMetalBorrowedTexture(target.toDescriptor(extent))
      is MetalSurfaceTarget -> map.attachMetalSurface(target.toDescriptor(extent))
      is OpenGlTextureTarget -> {
        target.makeContextCurrent()
        map.attachOpenGLBorrowedTexture(target.toDescriptor(extent))
      }
      is OpenGlSurfaceTarget -> map.attachOpenGLSurface(target.toDescriptor(extent))
    }

  /** Whether [session] took the replacement; a refusal leaves it rendering into its old texture. */
  private fun retargetBorrowedTexture(
    session: RenderSessionHandle,
    target: MlnFfiRenderTarget,
    extent: MapExtent,
  ): Boolean {
    try {
      when (target) {
        is VulkanImageTarget -> session.setVulkanBorrowedTextureTarget(target.toDescriptor(extent))
        is VulkanSurfaceTarget -> session.resize(extent.width, extent.height, extent.scaleFactor)
        is MetalTextureTarget -> session.setMetalBorrowedTextureTarget(target.toDescriptor(extent))
        is MetalSurfaceTarget -> session.setMetalSurfaceTarget(target.toDescriptor(extent))
        is OpenGlTextureTarget -> {
          target.makeContextCurrent()
          session.setOpenGLBorrowedTextureTarget(target.toDescriptor(extent))
        }
        is OpenGlSurfaceTarget -> session.setOpenGLSurfaceTarget(target.toDescriptor(extent))
      }
    } catch (error: InvalidArgumentException) {
      // A replacement belonging to another device.
      return refusedTarget(error)
    } catch (error: UnsupportedFeatureException) {
      // A replacement in another pixel format.
      return refusedTarget(error)
    }
    return true
  }

  private fun refusedTarget(error: MaplibreException): Boolean {
    core.logger?.d(error) {
      "The render session would not take the host's replacement target; re-attaching instead"
    }
    return false
  }

  /** MapLibre rejects a descriptor whose logical extent and physical size do not agree. */
  private fun MapExtent.toFfiExtent() =
    RenderTargetExtent(
      width = width.coerceAtLeast(1),
      height = height.coerceAtLeast(1),
      scaleFactor = scaleFactor,
    )

  private fun VulkanImageTarget.toDescriptor(extent: MapExtent) =
    VulkanBorrowedTextureDescriptor(
        extent = extent.toFfiExtent(),
        physicalWidth = extent.physicalWidth.coerceAtLeast(1),
        physicalHeight = extent.physicalHeight.coerceAtLeast(1),
        context = context.toFfi(),
        image = NativePointer.ofAddress(image.address),
        imageView = NativePointer.ofAddress(imageView.address),
        format = format,
        initialLayout = initialLayout,
      )
      .also { it.finalLayout = finalLayout }

  private fun VulkanSurfaceTarget.toDescriptor(extent: MapExtent) =
    VulkanSurfaceDescriptor(
      extent = extent.toFfiExtent(),
      context = context.toFfi(),
      surface = NativePointer.ofAddress(surface.address),
    )

  private fun MetalTextureTarget.toDescriptor(extent: MapExtent) =
    MetalBorrowedTextureDescriptor(
      extent = extent.toFfiExtent(),
      physicalWidth = extent.physicalWidth.coerceAtLeast(1),
      physicalHeight = extent.physicalHeight.coerceAtLeast(1),
      texture = NativePointer.ofAddress(texture.address),
    )

  private fun MetalSurfaceTarget.toDescriptor(extent: MapExtent) =
    MetalSurfaceDescriptor(
      extent = extent.toFfiExtent(),
      context = MetalContextDescriptor(device = NativePointer.ofAddress(device.address)),
      layer = NativePointer.ofAddress(layer.address),
    )

  private fun OpenGlTextureTarget.toDescriptor(extent: MapExtent) =
    OpenGLBorrowedTextureDescriptor(
      extent = extent.toFfiExtent(),
      physicalWidth = extent.physicalWidth.coerceAtLeast(1),
      physicalHeight = extent.physicalHeight.coerceAtLeast(1),
      context = context.toFfi(),
      texture = textureName,
      target = textureTarget,
    )

  private fun OpenGlSurfaceTarget.toDescriptor(extent: MapExtent) =
    OpenGLSurfaceDescriptor(
      extent = extent.toFfiExtent(),
      context = context.toFfi(),
      surface = NativePointer.ofAddress(surface.address),
    )

  // endregion

  // region frame pacing

  /**
   * The cap filters an arriving cadence rather than driving one, hence [FRAME_INTERVAL_SLACK]: a
   * cap at the display's own rate would otherwise halve the frame rate.
   */
  private fun allowRenderNow(now: TimeSource.Monotonic.ValueTimeMark): Boolean {
    val fps = core.maximumFps ?: return true
    if (fps <= 0) return true
    val minimumInterval = 1.0 / fps
    val elapsed = (now - lastRenderTime).toDouble(DurationUnit.SECONDS)
    return elapsed >= minimumInterval * (1.0 - FRAME_INTERVAL_SLACK)
  }

  private fun reportFrameRate() {
    val now = frameTimer.markNow()
    val elapsed = (now - lastFrameTime).toDouble(DurationUnit.SECONDS)
    lastFrameTime = now
    if (elapsed > 0.0) core.callbacks.onFrame(1.0 / elapsed)
  }

  // endregion

  /** The render session lives on the host's renderer thread. */
  private fun <T> withRendererAccess(action: () -> T): T? {
    val host = hostSession ?: return null
    return host.withRendererAccess(action)
  }
}

private fun VulkanContextHandles.toFfi() =
  org.maplibre.nativeffi.render.VulkanContextDescriptor(
    instance = NativePointer.ofAddress(instance.address),
    physicalDevice = NativePointer.ofAddress(physicalDevice.address),
    device = NativePointer.ofAddress(device.address),
    graphicsQueue = NativePointer.ofAddress(graphicsQueue.address),
    graphicsQueueFamilyIndex = graphicsQueueFamilyIndex,
    getInstanceProcAddr = NativePointer.ofAddress(getInstanceProcAddr.address),
    getDeviceProcAddr = NativePointer.ofAddress(getDeviceProcAddr.address),
  )

private fun OpenGlContextHandles.toFfi() =
  when (this) {
    is EglContextHandles -> toFfi()
    is WglContextHandles -> toFfi()
  }

private fun EglContextHandles.toFfi() =
  org.maplibre.nativeffi.render.EglContextDescriptor(
    display = NativePointer.ofAddress(display.address),
    config = NativePointer.ofAddress(config.address),
    shareContext =
      if (ownership == OpenGLContextOwnership.DEDICATED) NativePointer.NULL_POINTER
      else NativePointer.ofAddress(shareContext.address),
    getProcAddress = NativePointer.ofAddress(getProcAddress.address),
    clientApi =
      if (ownership == OpenGLContextOwnership.DEDICATED) {
        if (clientApi == OpenGLClientApi.UNSPECIFIED) OpenGLClientApi.GLES else clientApi
      } else {
        clientApi
      },
    ownership = ownership,
  )

private fun WglContextHandles.toFfi() =
  org.maplibre.nativeffi.render.WglContextDescriptor(
    deviceContext = NativePointer.ofAddress(deviceContext.address),
    shareContext =
      if (ownership == OpenGLContextOwnership.DEDICATED) NativePointer.NULL_POINTER
      else NativePointer.ofAddress(shareContext.address),
    getProcAddress = NativePointer.ofAddress(getProcAddress.address),
    ownership = ownership,
  )
