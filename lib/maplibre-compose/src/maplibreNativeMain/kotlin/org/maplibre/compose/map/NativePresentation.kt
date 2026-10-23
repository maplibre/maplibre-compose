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
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.nativeffi.error.MaplibreException
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
import org.maplibre.nativeffi.generated.RenderResult
import org.maplibre.nativeffi.generated.RenderSessionAttachOptions
import org.maplibre.nativeffi.generated.RenderSessionAttachment
import org.maplibre.nativeffi.generated.RenderSessionCapabilityFlag
import org.maplibre.nativeffi.generated.RenderSessionHandle
import org.maplibre.nativeffi.generated.RenderSessionState
import org.maplibre.nativeffi.generated.Status
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
 * Every target keeps a demand parked until the map changes. Hosts drain results and schedule the
 * next demand, including their frame-rate limit. Borrowed textures stay acquired until the host has
 * finished reading them.
 */
internal class NativePresentation(
  var backend: MapRenderBackend,
  private val isClosing: () -> Boolean,
  private val canRender: () -> Boolean,
  private val getLogger: () -> MapLog?,
  private val viewport: NativeViewport,
  private val onAttached: () -> Unit,
  private val postToMain: (() -> Unit) -> Unit,
) {
  private val logger
    get() = getLogger()

  private val stateLock = MlnFfiLock()
  private val pendingCloses = mutableListOf<Deferred<Result<Unit>>>()

  /** A host and every native borrow of its targets retire together on its renderer thread. */
  private inner class RendererAttachment(val host: MlnFfiMapHostSession) {
    /** Renderer-thread state, except that any thread may read a ready [session]. */
    @Volatile var session: RenderSessionHandle? = null
    var driver: RenderDriverKind = RenderDriverKind.CORE_WORKER
    var acquiresFrames = false
    @Volatile var ready = false
    @Volatile var target: MlnFfiRenderTarget? = null
    var demandPending = false
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
        frameWake =
          Wake {
            work.signal()
            if (!releaseRequested) host.requestFrame()
          },
        driverWorkWake =
          if (driver != RenderDriverKind.CALLER_GRAPHICS_THREAD) Wake()
          else
            Wake {
              work.signal()
              if (host.servicesDriverWorkOnFrame) {
                // Surface driver work includes parked demands. Service it at the display boundary
                // too, so a wake during a UI write burst cannot draw a partial update immediately.
                if (!releaseRequested) host.requestFrame()
              } else if (serviceQueued.compareAndSet(false, true)) {
                try {
                  if (
                    !host.enqueueRenderer {
                      serviceQueued.store(false)
                      service()
                    }
                  )
                    serviceQueued.store(false)
                } catch (error: Exception) {
                  serviceQueued.store(false)
                  logger?.d(error) { "Could not queue render session driver work" }
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
        current.serviceDriverWork()
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
          session.serviceDriverWork()
        }
        if (work.isCompleted) break
        this.work.await()
      }
      work.getCompletionExceptionOrNull()?.let { throw it }
      return work.getCompleted()
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
      demandPending = false
      var failure: Throwable? = null
      val retired =
        try {
          if (closing.getSnapshot().state != RenderSessionState.ABANDONED)
            await(closing, closing.detach())
          true
        } catch (error: Throwable) {
          // A frame that loses the target while the detach waits ends the detach with TARGET_LOST.
          val targetLost =
            error is MaplibreException &&
              (error.status == Status.TARGET_LOST ||
                error.status == Status.INVALID_STATE &&
                  closing.getSnapshot().state == RenderSessionState.TARGET_LOST)
          if (!targetLost) failure = error
          runCatching { closing.abandon() }
            .onFailure { abandoned ->
              failure?.addSuppressed(abandoned) ?: run { failure = abandoned }
            }
            .isSuccess
        }
      if (!retired) {
        // Neither detach nor abandon took, so native retires the session once it can.
        runCatching { closing.dispose() }.exceptionOrNull()?.let { failure?.addSuppressed(it) }
        failure?.let { throw it }
        return
      }
      val closed = CompletableDeferred<Result<Unit>>()
      stateLock.withLock { pendingCloses.add(closed) }
      // Queries submit on main too, so destruction cannot race a handle borrowed by a query.
      postToMain { closed.complete(runCatching { closing.close() }) }
      failure?.let { throw it }
    }

    fun release(): Result<Unit> {
      if (released.isCompleted) return released.getCompleted()
      releaseRequested = true
      return runCatching { closeSession() }.also { released.complete(it) }
    }

    private fun requestRelease() {
      if (released.isCompleted) return
      releaseRequested = true
      try {
        host.completeConsumerAccess()
      } finally {
        // Rejection means the host is shutting down. Its onSurfaceLost still owns this release.
        host.enqueueRenderer { release() }
      }
    }

    suspend fun releaseAndAwait() {
      requestRelease()
      var failure = released.await().exceptionOrNull()
      runCatching { awaitPendingCloses() }
        .exceptionOrNull()
        ?.let { error ->
          failure?.addSuppressed(error) ?: run { failure = error }
        }
      failure?.let { throw it }
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
      current
    }
    try {
      attachment.host.completeConsumerAccess()
    } finally {
      try {
        attachment.host.withRendererAccess { attachment.release().getOrThrow() }
      } finally {
        stateLock.withLock { if (rendererAttachment === attachment) rendererAttachment = null }
      }
    }
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
    val attachment = rendererAttachment
    if (attachment == null) {
      awaitPendingCloses()
      return
    }
    attachment.releaseAndAwait()
    if (!keepHost)
      stateLock.withLock {
        if (rendererAttachment === attachment) rendererAttachment = null
      }
  }

  private suspend fun awaitPendingCloses() {
    val closing = stateLock.withLock { pendingCloses.toList().also { pendingCloses.clear() } }
    var failure: Throwable? = null
    for (work in closing) {
      work.await().exceptionOrNull()?.let { error ->
        failure?.addSuppressed(error) ?: run { failure = error }
      }
    }
    failure?.let { throw it }
  }

  /** Host and extent changes schedule work on either kind of target. */
  fun requestRender() {
    val attachment = rendererAttachment ?: return
    attachment.host.requestFrame()
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
    val session = attachment.session ?: return MlnFfiFrameResult.AwaitUpdate
    if (host.servicesDriverWorkOnFrame) attachment.service()
    val result =
      try {
        val completed = session.drainFrameResults()?.use { it.get().results.lastOrNull() }
        if (completed != null) attachment.demandPending = false
        // A display-paced driver drains and re-arms in one pass, using one callback per demand.
        // Other hosts re-arm on their next pass, after any consumer has released its frame.
        if (!attachment.demandPending && (completed == null || host.servicesDriverWorkOnFrame)) {
          attachment.demandPending = true
          session.requestFrame(
            FrameDemand(
              flags =
                FrameDemandFlag.IF_NEEDED or
                  FrameDemandFlag.WAIT_FOR_UPDATE or
                  FrameDemandFlag.PRESENT,
            ),
          )
        }
        completed ?: return MlnFfiFrameResult.AwaitUpdate
      } catch (error: MaplibreException) {
        // A session whose target was lost rejects new demands; its state says so.
        // TODO: report why once https://github.com/maplibre/maplibre-native-ffi/issues/984 keeps
        // the reason a session lost its target.
        val state = runCatching { session.getSnapshot().state }.getOrNull()
        throw MlnFfiRecoverableFrameException(
          "The MapLibre render session failed in state $state",
          error,
        )
      }
    when (result.disposition) {
      RenderResult.RENDERED -> {
        // Replacement retires unacquired textures; a queued result can still describe the old one.
        if (result.generation != session.getSnapshot().generation)
          return MlnFfiFrameResult.RetryNextFrame
      }
      else -> return MlnFfiFrameResult.RetryNextFrame
    }
    var presentedView = result.view
    try {
      if (attachment.acquiresFrames) {
        val acquired = session.acquireFrame() ?: return MlnFfiFrameResult.RetryNextFrame
        try {
          presentedView = acquired.getResult().view
          acquired.withProducerSync { sync ->
            check(sync.kind == GpuSyncKind.CPU_COMPLETE) {
              "The host draws only frames whose producer work is complete, not ${sync.kind}"
            }
          }
        } catch (error: Throwable) {
          acquired.release()
          throw error
        }
        frame.releaseAfterReads = { consumerCompleted ->
          if (!consumerCompleted)
            attachment.host.withRendererAccess {
              if (attachment.session === session) session.abandon()
            }
          acquired.release()
        }
        attachment.host.requestFrame()
      }
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
      if (captureProjection) viewport.captureFrameProjection(presentedView, frame.target.extent)
      else null,
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
    val live = attachment.session
    if (
      attached?.generation == frame.target.generation && attached.extent == extent && live != null
    )
      return true

    attachment.ready = false
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
    onAttached()
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
    attachment.target = target
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
    } catch (error: MaplibreException) {
      // A replacement belonging to another device or in another pixel format.
      if (error.status != Status.INVALID_ARGUMENT && error.status != Status.UNSUPPORTED) throw error
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
          ),
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
        ),
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
        ),
      ),
  )
