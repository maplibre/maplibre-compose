@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalSurfaceTarget
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapHostSession
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
import org.maplibre.compose.mlnffi.withLock
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
import org.maplibre.nativeffi.render.VulkanHandle
import org.maplibre.nativeffi.render.VulkanSurfaceDescriptor

/** One host attachment owns its renderer handle and every borrow of its GPU target. */
internal class NativePresentation(
  private val backend: MapRenderBackend,
  private val isClosing: () -> Boolean,
  private val canRender: () -> Boolean,
  private val getLogger: () -> MapLog?,
  private val viewport: NativeViewport,
  private val requestViewport: (MapExtent) -> Unit,
) {
  private val logger
    get() = getLogger()

  private val stateLock = MlnFfiLock()

  /** A host and every native borrow of its targets retire together on its renderer thread. */
  private class RendererAttachment(val host: MlnFfiMapHostSession) {
    var handle: RenderSessionHandle? = null
    var ready = false
    var target: MlnFfiRenderTarget? = null
    @Volatile var releaseRequested = false
    val released = CompletableDeferred<Result<Unit>>()

    fun closeHandle() {
      handle?.close()
      handle = null
      ready = false
      target = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun release(): Result<Unit> {
      if (released.isCompleted) return released.getCompleted()
      releaseRequested = true
      return runCatching { closeHandle() }.also { released.complete(it) }
    }

    private fun requestRelease() {
      releaseRequested = true
      // Rejection means the host is shutting down. Its onSurfaceLost still owns this release.
      host.enqueueRenderer { release() }
    }

    suspend fun releaseAndAwait() {
      requestRelease()
      released.await().getOrThrow()
    }

    /** Host handoff runs on the incoming host's renderer context, never its native map owner. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun releaseBeforeHandoff() {
      requestRelease()
      if (!released.isCompleted) {
        val done = MlnFfiGate()
        released.invokeOnCompletion { done.open() }
        done.awaitUntilOpen()
      }
      released.getCompleted().getOrThrow()
    }
  }

  @Volatile private var rendererAttachment: RendererAttachment? = null
  /** Renderer-thread state, read by tests. */
  @Volatile
  internal var attachCount: Int = 0
    private set

  @Volatile
  internal var retargetCount: Int = 0
    private set

  private val renderRequested = AtomicBoolean(true)

  /** Renderer-thread state, read by tests. */
  @Volatile
  internal var hasRenderedAFrame: Boolean = false
    private set

  fun onSurfaceAvailable(session: MlnFfiMapHostSession) {
    while (!isClosing() && !session.isClosed) {
      val previous = stateLock.withLock { rendererAttachment }
      previous?.releaseBeforeHandoff()
      val published = stateLock.withLock {
        if (rendererAttachment !== previous) false
        else {
          if (isClosing() || session.isClosed) return
          rendererAttachment = RendererAttachment(session)
          true
        }
      }
      if (published) {
        requestRender()
        return
      }
    }
  }

  fun onSurfaceLost(session: MlnFfiMapHostSession) {
    // The render session must go before the host session is dropped: that is the only route to the
    // thread allowed to close the handle.
    logger?.i { "Host surface lost; closing the render session and waiting for a new one" }
    val attachment = stateLock.withLock {
      val current = rendererAttachment?.takeIf { it.host === session } ?: return
      viewport.clearRequest()
      current
    }
    attachment.host.withRendererAccess { attachment.release().getOrThrow() }
    stateLock.withLock { if (rendererAttachment === attachment) rendererAttachment = null }
  }

  suspend fun resume() {
    val attachment = rendererAttachment
    if (attachment?.releaseRequested == true) {
      attachment.released.await().getOrThrow()
      attachment.host.enqueueRenderer {
        stateLock.withLock {
          if (rendererAttachment === attachment && !isClosing() && !attachment.host.isClosed) {
            rendererAttachment = RendererAttachment(attachment.host)
          }
        }
        requestRender()
      }
    } else requestRender()
  }

  suspend fun release(keepHost: Boolean = true) {
    val attachment = rendererAttachment ?: return
    attachment.releaseAndAwait()
    if (!keepHost)
      stateLock.withLock {
        if (rendererAttachment === attachment) rendererAttachment = null
      }
  }

  fun requestRender() {
    renderRequested.store(true)
    rendererAttachment?.host?.requestFrame()
  }

  fun render(
    host: MlnFfiMapHostSession,
    map: MapHandle,
    frame: MlnFfiMapFrame,
    captureProjection: Boolean,
  ): MlnFfiFrameResult {
    val attachment =
      rendererAttachment?.takeIf { it.host === host } ?: return MlnFfiFrameResult.AwaitUpdate
    if (frame.target.extent.isEmpty) return MlnFfiFrameResult.AwaitUpdate

    viewport.captureRenderPadding()
    if (!ensureAttached(attachment, map, frame)) return MlnFfiFrameResult.AwaitUpdate
    // Consumed before rendering, so an update published during the render below is not discarded.
    if (!renderRequested.exchange(false)) return MlnFfiFrameResult.AwaitUpdate
    val session = attachment.handle ?: return MlnFfiFrameResult.AwaitUpdate
    val update =
      try {
        session.renderUpdate()
      } catch (error: NativeErrorException) {
        throw MlnFfiRecoverableFrameException("The MapLibre render session failed", error)
      }
    if (update.result == RenderResult.RENDERED) {
      attachment.ready = true
    }
    when (update.result) {
      RenderResult.NO_UPDATE,
      RenderResult.SIZE_PENDING -> return MlnFfiFrameResult.AwaitUpdate
      RenderResult.TARGET_NOT_READY -> {
        renderRequested.store(true)
        return MlnFfiFrameResult.RetryNextFrame
      }
      else -> Unit
    }
    // Native advances transitions after frame completion on the map owner, then publishes a
    // MAP_RENDER_UPDATE_AVAILABLE event. Wait for that update instead of drawing the same
    // snapshot again with its old transition time.

    if (!hasRenderedAFrame) {
      hasRenderedAFrame = true
      logger?.i {
        "Rendered the first map frame with $backend on ${currentMlnFfiThreadName()}, " +
          "extent ${frame.target.extent}"
      }
    }
    return MlnFfiFrameResult.Rendered(
      if (captureProjection) viewport.captureFrameProjection(session, frame.target.extent) else null
    )
  }

  /**
   * Renderer thread only. Re-reads the lifecycle: teardown marks the session closing before it
   * queues its release on this thread, and a session attached behind that release is never closed.
   */
  private fun ensureAttached(
    attachment: RendererAttachment,
    map: MapHandle,
    frame: MlnFfiMapFrame,
  ): Boolean {
    val extent = frame.target.extent
    if (!canRender() || attachment.releaseRequested) return false
    val attached = attachment.target
    if (
      attached?.generation == frame.target.generation &&
        attached.extent == extent &&
        attachment.handle != null
    )
      return true

    // A renderer compiles its shaders for one pixel ratio, so a scale-factor change needs a new
    // one.
    val live = attachment.handle
    if (
      live != null &&
        attached != null &&
        attached.extent.scaleFactor == extent.scaleFactor &&
        retargetBorrowedTexture(live, frame.target, extent)
    ) {
      retargetCount++
    } else {
      // A map permits one render session; release it before borrowing the replacement target.
      attachment.closeHandle()
      attachment.handle =
        try {
          attachBorrowedTexture(map, frame.target, extent)
        } catch (error: Throwable) {
          logger?.e(error) { "Failed to attach a render session to the host target" }
          throw error
        }
      attachCount++
    }
    attachment.target = frame.target
    requestViewport(extent)
    // A new target needs a frame even when the map is idle.
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
    logger?.d(error) {
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
        image = VulkanHandle.ofBits(image.address),
        imageView = VulkanHandle.ofBits(imageView.address),
        format = format,
        initialLayout = initialLayout,
      )
      .also { it.finalLayout = finalLayout }

  private fun VulkanSurfaceTarget.toDescriptor(extent: MapExtent) =
    VulkanSurfaceDescriptor(
      extent = extent.toFfiExtent(),
      context = context.toFfi(),
      surface = VulkanHandle.ofBits(surface.address),
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

  /**
   * Runs [action] on the renderer thread with the ready render session, and suspends until it
   * returns. Returns null when no render session is ready or the host can no longer run [action].
   */
  suspend fun <T> awaitRenderSession(action: (RenderSessionHandle) -> T): T? =
    suspendCancellableCoroutine { continuation ->
      val attachment = rendererAttachment
      if (isClosing() || attachment == null) {
        continuation.resume(null)
        return@suspendCancellableCoroutine
      }
      val accepted =
        attachment.host.enqueueRenderer {
          if (!continuation.isActive) return@enqueueRenderer
          val session = attachment.handle
          if (
            isClosing() ||
              rendererAttachment !== attachment ||
              attachment.releaseRequested ||
              session == null ||
              !attachment.ready
          ) {
            continuation.resume(null)
          } else {
            continuation.resumeWith(runCatching { action(session) })
          }
        }
      if (!accepted && continuation.isActive) continuation.resume(null)
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
