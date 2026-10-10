@file:OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalSurfaceTarget
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiFrameResult
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapHostSession
import org.maplibre.compose.mlnffi.MlnFfiRecoverableFrameException
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.MlnFfiSignal
import org.maplibre.compose.mlnffi.OpenGlContextHandles
import org.maplibre.compose.mlnffi.OpenGlSurfaceTarget
import org.maplibre.compose.mlnffi.OpenGlTextureTarget
import org.maplibre.compose.mlnffi.VulkanContextHandles
import org.maplibre.compose.mlnffi.VulkanImageTarget
import org.maplibre.compose.mlnffi.VulkanSurfaceTarget
import org.maplibre.compose.mlnffi.WglContextHandles
import org.maplibre.compose.mlnffi.currentMlnFfiThreadName
import org.maplibre.compose.mlnffi.reportFailure
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.nativeffi.error.InvalidArgumentException
import org.maplibre.nativeffi.error.MaplibreException
import org.maplibre.nativeffi.error.MaplibreStatus
import org.maplibre.nativeffi.error.UnsupportedFeatureException
import org.maplibre.nativeffi.generated.EglContextDescriptor
import org.maplibre.nativeffi.generated.FrameDemand
import org.maplibre.nativeffi.generated.FrameDemandFlag
import org.maplibre.nativeffi.generated.GpuSyncKind
import org.maplibre.nativeffi.generated.LogicalExtent
import org.maplibre.nativeffi.generated.MapHandle
import org.maplibre.nativeffi.generated.MetalBorrowedTexture
import org.maplibre.nativeffi.generated.MetalBorrowedTextureDescriptor
import org.maplibre.nativeffi.generated.MetalContextDescriptor
import org.maplibre.nativeffi.generated.MetalSurfaceDescriptor
import org.maplibre.nativeffi.generated.OpenglBorrowedTexture
import org.maplibre.nativeffi.generated.OpenglBorrowedTextureDescriptor
import org.maplibre.nativeffi.generated.OpenglClientApi
import org.maplibre.nativeffi.generated.OpenglContextDescriptor
import org.maplibre.nativeffi.generated.OpenglContextDescriptorData
import org.maplibre.nativeffi.generated.OpenglContextOwnership
import org.maplibre.nativeffi.generated.OpenglSurfaceDescriptor
import org.maplibre.nativeffi.generated.RenderDriverKind
import org.maplibre.nativeffi.generated.RenderFrameResult
import org.maplibre.nativeffi.generated.RenderResult
import org.maplibre.nativeffi.generated.RenderSessionAttachOptions
import org.maplibre.nativeffi.generated.RenderSessionAttachment
import org.maplibre.nativeffi.generated.RenderSessionCapabilityFlag
import org.maplibre.nativeffi.generated.RenderSessionHandle
import org.maplibre.nativeffi.generated.VulkanBorrowedTexture
import org.maplibre.nativeffi.generated.VulkanBorrowedTextureDescriptor
import org.maplibre.nativeffi.generated.VulkanContextDescriptor
import org.maplibre.nativeffi.generated.VulkanSurfaceDescriptor
import org.maplibre.nativeffi.generated.Wake
import org.maplibre.nativeffi.generated.WglContextDescriptor
import org.maplibre.nativeffi.render.NativePointer

/**
 * One host attachment owns its render session and every borrow of its GPU target.
 *
 * Frames are rendered synchronously inside [render] on the host's renderer thread: it demands one
 * frame and waits for that demand's result. A Metal session renders on its native core worker
 * meanwhile. An OpenGL or Vulkan session renders on the renderer thread itself, which services the
 * session's driver work while it waits, and again whenever the session wakes it outside a frame,
 * such as for a feature query.
 */
internal class NativePresentation(
  private val backend: MapRenderBackend,
  /** The scale factor the map was created with, which a map resize must keep. */
  private val mapScaleFactor: Double,
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
  private inner class RendererAttachment(val host: MlnFfiMapHostSession) {
    /** Renderer-thread state, except that any thread may read a ready [session]. */
    @Volatile var session: RenderSessionHandle? = null
    var driver: RenderDriverKind = RenderDriverKind.CORE_WORKER
    var acquiresFrames = false
    @Volatile var ready = false
    var target: MlnFfiRenderTarget? = null
    var nextToken = 0uL
    @Volatile var releaseRequested = false
    val released = CompletableDeferred<Result<Unit>>()

    /** Wakes the renderer thread while it waits on the session. */
    val work = MlnFfiSignal()

    /** Set while service of driver work that arrived outside a frame is queued. */
    val serviceQueued = AtomicBoolean(false)

    val callerDriven: Boolean
      get() = driver == RenderDriverKind.CALLER_GRAPHICS_THREAD

    fun attachOptions(driver: RenderDriverKind) =
      RenderSessionAttachOptions(
        driver = driver,
        frameWake = Wake { work.signal() },
        driverWorkWake =
          if (driver != RenderDriverKind.CALLER_GRAPHICS_THREAD) Wake()
          else
            Wake {
              work.signal()
              // Work that arrives between frames, such as a query, needs the renderer thread.
              if (serviceQueued.compareAndSet(false, true)) {
                host.enqueueRenderer {
                  serviceQueued.store(false)
                  service()
                }
              }
            },
      )

    /**
     * Renderer thread. Runs the session's queued driver work when the renderer thread drives it.
     */
    fun service() {
      val current = session ?: return
      if (!callerDriven) return
      try {
        (target as? OpenGlTextureTarget)?.makeContextCurrent?.invoke()
        current.serviceDriverWork(0uL)
      } catch (error: MaplibreException) {
        // A session closing on this thread has nothing left to service.
        logger?.d(error) { "Render session driver service failed" }
      }
    }

    /**
     * Renderer thread. Waits for [work], driving the session meanwhile when it needs this thread.
     */
    fun <T> await(session: RenderSessionHandle, work: Deferred<T>): T {
      work.invokeOnCompletion { this.work.signal() }
      while (!work.isCompleted) {
        if (callerDriven) {
          (target as? OpenGlTextureTarget)?.makeContextCurrent?.invoke()
          session.serviceDriverWork(0uL)
        }
        if (work.isCompleted) break
        this.work.await()
      }
      work.getCompletionExceptionOrNull()?.let { throw it }
      return work.getCompleted()
    }

    /** Renderer thread. Waits for the result of the demand with [token]. */
    fun awaitResult(session: RenderSessionHandle, token: ULong): RenderFrameResult {
      while (true) {
        if (callerDriven) session.serviceDriverWork(0uL)
        val result =
          session.drainFrameResults()?.use { batch ->
            batch.get().results.lastOrNull { it.token == token }
          }
        if (result != null) return result
        work.await()
      }
    }

    /**
     * Renderer thread. Detaches the session and destroys it. A detach that fails abandons the
     * session instead, which releases what it can without this thread's graphics context.
     */
    fun closeSession() {
      val closing = session ?: return
      session = null
      ready = false
      target = null
      var failure: Throwable? = null
      try {
        await(closing, closing.detach())
      } catch (error: Throwable) {
        failure = error
        runCatching { closing.abandon() }.exceptionOrNull()?.let(error::addSuppressed)
      }
      // A query that is being submitted holds the handle for that call, so a close can race it.
      while (true) {
        try {
          closing.close()
          break
        } catch (error: MaplibreException) {
          if (error.status != MaplibreStatus.INVALID_STATE || closing.isClosed) {
            failure?.addSuppressed(error) ?: run { failure = error }
            break
          }
        }
      }
      failure?.let { throw it }
    }

    fun release(): Result<Unit> {
      if (released.isCompleted) return released.getCompleted()
      releaseRequested = true
      return runCatching { closeSession() }.also { released.complete(it) }
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

    /** Host handoff runs on the incoming host's renderer thread, never on the outgoing one. */
    fun releaseBeforeHandoff() {
      requestRelease()
      if (!released.isCompleted) {
        val done = MlnFfiSignal()
        released.invokeOnCompletion { done.signal() }
        while (!released.isCompleted) done.await()
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

  /** Set when the next frame renders even without a newer map update. */
  private val renderForced = AtomicBoolean(true)

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
    // thread allowed to drive the session's teardown.
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

  /** Asks for a frame of the map's next update. Callable from any thread. */
  fun requestRender() {
    renderRequested.store(true)
    rendererAttachment?.host?.requestFrame()
  }

  /** Asks for a frame even when the map has no newer update. Callable from any thread. */
  fun requestRepaint() {
    renderForced.store(true)
    requestRender()
  }

  /** The attached render session, once it has drawn a frame, for queries from any thread. */
  /** The host session presented into, if any. */
  val host: MlnFfiMapHostSession?
    get() = rendererAttachment?.host

  fun readySession(): RenderSessionHandle? {
    val attachment = rendererAttachment ?: return null
    if (isClosing() || attachment.releaseRequested || !attachment.ready) return null
    return attachment.session
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
    val forced = renderForced.exchange(false)
    if (!renderRequested.exchange(false) && !forced) return MlnFfiFrameResult.AwaitUpdate
    val session = attachment.session ?: return MlnFfiFrameResult.AwaitUpdate
    val result =
      try {
        val token = ++attachment.nextToken
        session.requestFrame(
          FrameDemand(
            flags =
              if (forced) FrameDemandFlag.PRESENT
              else FrameDemandFlag.IF_NEEDED or FrameDemandFlag.PRESENT,
            token = token,
          )
        )
        attachment.awaitResult(session, token)
      } catch (error: MaplibreException) {
        throw MlnFfiRecoverableFrameException("The MapLibre render session failed", error)
      }
    if (result.needsRepaint) requestRepaint()
    when (result.disposition) {
      RenderResult.RENDERED -> Unit
      RenderResult.TARGET_NOT_READY -> {
        // A target that is not ready publishes no map update, so the retry renders regardless.
        renderForced.store(true)
        renderRequested.store(true)
        return MlnFfiFrameResult.RetryNextFrame
      }
      else -> return MlnFfiFrameResult.AwaitUpdate
    }
    try {
      if (attachment.acquiresFrames) releaseRenderedFrames(session)
    } catch (error: MaplibreException) {
      throw MlnFfiRecoverableFrameException("The MapLibre render session failed", error)
    }
    attachment.ready = true

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
   * Hands every rendered frame of the borrowed ring back. The host draws the texture it lent after
   * this returns, as it did before rings existed, so the frame is released at once. Its producer
   * synchronization is CPU-complete: the GPU finished writing before the frame became acquirable.
   */
  private fun releaseRenderedFrames(session: RenderSessionHandle) {
    while (true) {
      val frame = session.acquireFrame() ?: return
      try {
        frame.withProducerSync { sync ->
          check(sync.kind == GpuSyncKind.CPU_COMPLETE) {
            "The host draws only frames whose producer work is complete, not ${sync.kind}"
          }
        }
      } finally {
        frame.release()
      }
    }
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
    val live = attachment.session
    if (
      attached?.generation == frame.target.generation && attached.extent == extent && live != null
    )
      return true

    // A renderer compiles its shaders for one pixel ratio, so a scale-factor change needs a new
    // one.
    val retargeted =
      live != null &&
        attached != null &&
        attached.extent.scaleFactor == extent.scaleFactor &&
        retarget(attachment, live, frame.target, extent)
    if (retargeted) {
      retargetCount++
    } else {
      // A map permits one render session; release it before borrowing the replacement target.
      attachment.closeSession()
      try {
        attach(attachment, map, frame.target, extent)
      } catch (error: Throwable) {
        logger?.e(error) { "Failed to attach a render session to the host target" }
        throw error
      }
      attachCount++
    }
    attachment.target = frame.target
    // Attaching or replacing a target sizes the session, not the map. A session resize submits the
    // map's resize itself; every other target needs the host to resize the map to match.
    if (!retargeted || frame.target !is VulkanSurfaceTarget) {
      map
        .resize(extent.toFfiExtent().copy(scaleFactor = mapScaleFactor))
        .reportFailure(logger, "resize the map to its target")
    }
    requestViewport(extent)
    // A new target needs a frame even when the map is idle.
    renderForced.store(true)
    return true
  }

  private fun driverFor(target: MlnFfiRenderTarget): RenderDriverKind =
    when (target) {
      // Metal queues are thread-safe, so the session renders on its own worker.
      is MetalTextureTarget,
      is MetalSurfaceTarget -> RenderDriverKind.CORE_WORKER
      // OpenGL requires the thread whose context the host made current. A Vulkan session shares
      // its queue with the host's own submissions on this thread.
      else -> RenderDriverKind.CALLER_GRAPHICS_THREAD
    }

  private fun attach(
    attachment: RendererAttachment,
    map: MapHandle,
    target: MlnFfiRenderTarget,
    extent: MapExtent,
  ) {
    val driver = driverFor(target)
    val options = attachment.attachOptions(driver)
    val started: RenderSessionAttachment =
      when (target) {
        is VulkanImageTarget ->
          map.attachVulkanBorrowedTexture(target.toDescriptor(extent), options)
        is VulkanSurfaceTarget -> map.attachVulkanSurface(target.toDescriptor(extent), options)
        is MetalTextureTarget ->
          map.attachMetalBorrowedTexture(target.toDescriptor(extent), options)
        is MetalSurfaceTarget -> map.attachMetalSurface(target.toDescriptor(extent), options)
        is OpenGlTextureTarget -> {
          target.makeContextCurrent()
          map.attachOpenglBorrowedTexture(target.toDescriptor(extent), options)
        }
        is OpenGlSurfaceTarget -> map.attachOpenglSurface(target.toDescriptor(extent), options)
      }
    attachment.session = started.session
    attachment.driver = driver
    attachment.target = target
    try {
      attachment.await(started.session, started.ready)
      attachment.acquiresFrames =
        RenderSessionCapabilityFlag.FRAME_ACQUISITION in started.session.getCapabilities().flags
    } catch (error: Throwable) {
      // A failed attachment still needs detach or abandon before the session can retire.
      runCatching { attachment.closeSession() }.exceptionOrNull()?.let(error::addSuppressed)
      throw error
    }
  }

  /** Whether [session] took the replacement; a refusal leaves it rendering into its old target. */
  private fun retarget(
    attachment: RendererAttachment,
    session: RenderSessionHandle,
    target: MlnFfiRenderTarget,
    extent: MapExtent,
  ): Boolean {
    try {
      val replacement: Deferred<*> =
        when (target) {
          is VulkanImageTarget ->
            session.setVulkanBorrowedTextureTarget(target.toDescriptor(extent))
          is VulkanSurfaceTarget -> session.resize(extent.toFfiExtent())
          is MetalTextureTarget ->
            session.setMetalBorrowedTextureTarget(target.toDescriptor(extent))
          is MetalSurfaceTarget -> session.setMetalSurfaceTarget(target.toDescriptor(extent))
          is OpenGlTextureTarget -> {
            target.makeContextCurrent()
            session.setOpenglBorrowedTextureTarget(target.toDescriptor(extent))
          }
          is OpenGlSurfaceTarget -> session.setOpenglSurfaceTarget(target.toDescriptor(extent))
        }
      attachment.await(session, replacement)
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
    LogicalExtent(
      width = width.coerceAtLeast(1).toUInt(),
      height = height.coerceAtLeast(1).toUInt(),
      scaleFactor = scaleFactor,
    )

  private fun VulkanImageTarget.toDescriptor(extent: MapExtent) =
    VulkanBorrowedTextureDescriptor(
      extent = extent.toFfiExtent(),
      physicalWidth = extent.physicalWidth.coerceAtLeast(1).toUInt(),
      physicalHeight = extent.physicalHeight.coerceAtLeast(1).toUInt(),
      context = context.toFfi(),
      textures =
        listOf(
          VulkanBorrowedTexture(
            image = image.address.toULong(),
            imageView = imageView.address.toULong(),
          )
        ),
      format = format.toUInt(),
      initialLayout = initialLayout.toUInt(),
      finalLayout = finalLayout.toUInt(),
    )

  private fun VulkanSurfaceTarget.toDescriptor(extent: MapExtent) =
    VulkanSurfaceDescriptor(
      extent = extent.toFfiExtent(),
      context = context.toFfi(),
      surface = surface.address.toULong(),
    )

  private fun MetalTextureTarget.toDescriptor(extent: MapExtent) =
    MetalBorrowedTextureDescriptor(
      extent = extent.toFfiExtent(),
      physicalWidth = extent.physicalWidth.coerceAtLeast(1).toUInt(),
      physicalHeight = extent.physicalHeight.coerceAtLeast(1).toUInt(),
      textures = listOf(MetalBorrowedTexture(NativePointer.ofAddress(texture.address))),
    )

  private fun MetalSurfaceTarget.toDescriptor(extent: MapExtent) =
    MetalSurfaceDescriptor(
      extent = extent.toFfiExtent(),
      context = MetalContextDescriptor(device = NativePointer.ofAddress(device.address)),
      layer = NativePointer.ofAddress(layer.address),
    )

  private fun OpenGlTextureTarget.toDescriptor(extent: MapExtent) =
    OpenglBorrowedTextureDescriptor(
      extent = extent.toFfiExtent(),
      physicalWidth = extent.physicalWidth.coerceAtLeast(1).toUInt(),
      physicalHeight = extent.physicalHeight.coerceAtLeast(1).toUInt(),
      context = context.toFfi(),
      textures = listOf(OpenglBorrowedTexture(textureName.toUInt())),
      target = textureTarget.toUInt(),
    )

  private fun OpenGlSurfaceTarget.toDescriptor(extent: MapExtent) =
    OpenglSurfaceDescriptor(
      extent = extent.toFfiExtent(),
      context = context.toFfi(),
      surface = NativePointer.ofAddress(surface.address),
    )
}

internal fun VulkanContextHandles.toFfi() =
  VulkanContextDescriptor(
    instance = NativePointer.ofAddress(instance.address),
    physicalDevice = NativePointer.ofAddress(physicalDevice.address),
    device = NativePointer.ofAddress(device.address),
    graphicsQueue = NativePointer.ofAddress(graphicsQueue.address),
    graphicsQueueFamilyIndex = graphicsQueueFamilyIndex.toUInt(),
    getInstanceProcAddr = NativePointer.ofAddress(getInstanceProcAddr.address),
    getDeviceProcAddr = NativePointer.ofAddress(getDeviceProcAddr.address),
  )

internal fun OpenGlContextHandles.toFfi(): OpenglContextDescriptor =
  when (this) {
    is EglContextHandles -> toFfi()
    is WglContextHandles -> toFfi()
  }

internal fun EglContextHandles.toFfi(): OpenglContextDescriptor {
  val dedicated = ownership == OpenglContextOwnership.DEDICATED
  return OpenglContextDescriptor(
    ownership = ownership,
    data =
      OpenglContextDescriptorData.Egl(
        EglContextDescriptor(
          display = NativePointer.ofAddress(display.address),
          config = NativePointer.ofAddress(config.address),
          shareContext =
            if (dedicated) NativePointer.NULL_POINTER
            else NativePointer.ofAddress(shareContext.address),
          clientApi =
            if (dedicated && clientApi == OpenglClientApi.UNSPECIFIED) OpenglClientApi.GLES
            else clientApi,
          getProcAddress = NativePointer.ofAddress(getProcAddress.address),
        )
      ),
  )
}

internal fun WglContextHandles.toFfi(): OpenglContextDescriptor =
  OpenglContextDescriptor(
    ownership = ownership,
    data =
      OpenglContextDescriptorData.Wgl(
        WglContextDescriptor(
          deviceContext = NativePointer.ofAddress(deviceContext.address),
          shareContext =
            if (ownership == OpenglContextOwnership.DEDICATED) NativePointer.NULL_POINTER
            else NativePointer.ofAddress(shareContext.address),
          getProcAddress = NativePointer.ofAddress(getProcAddress.address),
        )
      ),
  )
