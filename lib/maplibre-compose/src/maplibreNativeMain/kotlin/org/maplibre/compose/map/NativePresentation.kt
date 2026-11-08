@file:OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.mlnffi.EglContextHandles
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalSurfaceTarget
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiFrameDemands
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
import org.maplibre.compose.mlnffi.PlatformWindowTarget
import org.maplibre.compose.mlnffi.VulkanContextHandles
import org.maplibre.compose.mlnffi.WglContextHandles
import org.maplibre.compose.mlnffi.currentMlnFfiThreadName
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.nativeffi.Completion
import org.maplibre.nativeffi.EglContextDescriptor
import org.maplibre.nativeffi.FrameDemand
import org.maplibre.nativeffi.FrameDemandFlag
import org.maplibre.nativeffi.FramePacerHandle
import org.maplibre.nativeffi.GpuSync
import org.maplibre.nativeffi.GpuSyncKind
import org.maplibre.nativeffi.MapHandle
import org.maplibre.nativeffi.MapView
import org.maplibre.nativeffi.MaplibreException
import org.maplibre.nativeffi.NativePointer
import org.maplibre.nativeffi.OpenGlBorrowedTexture
import org.maplibre.nativeffi.OpenGlBorrowedTextureDescriptor
import org.maplibre.nativeffi.OpenGlBorrowedTextureReplacement
import org.maplibre.nativeffi.OpenGlClientApi
import org.maplibre.nativeffi.OpenGlContextDescriptor
import org.maplibre.nativeffi.OpenGlContextDescriptorData
import org.maplibre.nativeffi.OpenGlContextOwnership
import org.maplibre.nativeffi.OpenGlSurfaceDescriptor
import org.maplibre.nativeffi.OpenGlSurfaceReplacement
import org.maplibre.nativeffi.RenderDriverKind
import org.maplibre.nativeffi.RenderResult
import org.maplibre.nativeffi.RenderSessionAttachOptions
import org.maplibre.nativeffi.RenderSessionCapabilityFlag
import org.maplibre.nativeffi.RenderSessionHandle
import org.maplibre.nativeffi.Status
import org.maplibre.nativeffi.VulkanContextDescriptor
import org.maplibre.nativeffi.Wake
import org.maplibre.nativeffi.WglContextDescriptor

/**
 * One host attachment owns its render session and every borrow of its GPU target.
 *
 * By default, every target keeps a demand parked until the map changes. Hosts drain results and
 * schedule the next demand, including their frame-rate limit. A host can instead have a frame pacer
 * or its own display clock demand the frames, as [MlnFfiFrameDemands] describes. Borrowed textures
 * stay acquired until the host has finished reading them.
 *
 * A host that presents to a [PlatformWindowTarget] can keep its session when its window goes away:
 * [releaseTarget] lets go of the window, and the next frame gives the session the host's next one.
 */
internal class NativePresentation(
  var backend: MapRenderBackend,
  private val isClosing: () -> Boolean,
  private val canRender: () -> Boolean,
  private val getLogger: () -> MapLog?,
  private val viewport: NativeViewport,
  private val onAttached: () -> Unit,
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
    @Volatile var target: MlnFfiRenderTarget? = null
    var demandPending = false
    /** The frame pacer that demands the session's frames, when the host has one. */
    var pacer: FramePacerHandle? = null
    /** Whether the session let go of its window, and waits for the host's next one. */
    var targetReleased = false
    /** Set by the redraw wake. The next frame pass demands a frame. */
    val redrawRequested = AtomicBoolean(false)
    @Volatile var releaseRequested = false
    val released = CompletableDeferred<Result<Unit>>()

    /** Wakes the renderer thread while it waits on the session. */
    val work = MlnFfiSignal()

    /** Set while service of driver work that arrived outside a frame is queued. */
    val serviceQueued = AtomicBoolean(false)

    val callerDriven: Boolean
      get() = driver == RenderDriverKind.CALLER_GRAPHICS_THREAD

    fun attachOptions(driver: RenderDriverKind, demands: MlnFfiFrameDemands) =
      RenderSessionAttachOptions(
        driver = driver,
        frameWake =
          Wake {
            work.signal()
            if (!releaseRequested) host.requestFrame()
          },
        // A paced session can't have a redraw wake.
        redrawWake =
          if (demands != MlnFfiFrameDemands.OnRedraw) Wake()
          else
            Wake {
              redrawRequested.store(true)
              if (!releaseRequested) host.requestFrame()
            },
        driverWorkWake =
          if (driver != RenderDriverKind.CALLER_GRAPHICS_THREAD) Wake()
          else
            Wake {
              work.signal()
              if (serviceQueued.compareAndSet(false, true)) {
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
     * Renderer thread. Waits for [work], driving the session meanwhile when it needs this thread. A
     * released session still needs its driver work serviced until the release completes.
     */
    fun <T> await(session: RenderSessionHandle, completion: Completion<T>): T {
      val work = completion.asDeferred()
      work.invokeOnCompletion { this.work.signal() }
      while (!work.isCompleted) {
        if (callerDriven) {
          (target as? OpenGlTextureTarget)?.makeContextCurrent?.invoke()
          try {
            session.serviceDriverWork()
          } catch (error: MaplibreException) {
            // A release that finished meanwhile no longer takes service; its completion follows.
            if (error.status != Status.INVALID_STATE) throw error
          }
        }
        if (work.isCompleted) break
        this.work.await()
      }
      work.getCompletionExceptionOrNull()?.let { throw it }
      return work.getCompleted()
    }

    /**
     * Renderer thread. Finishes driver work before releasing the host's graphics objects. The
     * release detaches an attached session first, and abandons one that failed or was abandoned.
     */
    fun closeSession() {
      val closing = session ?: return
      session = null
      ready = false
      demandPending = false
      pacer = null
      targetReleased = false
      redrawRequested.store(false)
      try {
        await(closing, closing.release())
      } finally {
        target = null
      }
    }

    /**
     * Renderer thread. Lets the session go of its window and keeps the session, its renderer, and
     * its tiles for the host's next window. Only a [PlatformWindowTarget] session gets here.
     *
     * The session keeps its own reference to the window, but Android's `SurfaceView` destroys the
     * window's surface once `surfaceDestroyed` returns, and Android 8.0 crashes if the session
     * still draws into it then. So this waits until the session has stopped, as the FFI's own
     * Android example does. A session that can't let go of its window is released whole, and the
     * next window attaches a new one.
     */
    fun releaseTarget() {
      val current = session ?: return
      if (targetReleased || releaseRequested) return
      targetReleased = true
      ready = false
      try {
        await(current, current.releaseTarget())
      } catch (error: MaplibreException) {
        logger?.d(error) { "The render session could not release its window; releasing it" }
        closeSession()
      }
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

  /** The host [session] lost its window but stays. Its render session waits for the next window. */
  fun releaseTarget(session: MlnFfiMapHostSession) {
    val attachment =
      stateLock.withLock { rendererAttachment?.takeIf { it.host === session } } ?: return
    attachment.host.withRendererAccess { attachment.releaseTarget() }
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
    if (attachment == null) return
    attachment.releaseAndAwait()
    if (!keepHost)
      stateLock.withLock {
        if (rendererAttachment === attachment) rendererAttachment = null
      }
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
    val demands = host.frameDemands
    if (demands != MlnFfiFrameDemands.Parked) {
      return takeDemandedFrames(attachment, session, demands, frame, captureProjection)
    }
    val result =
      try {
        val completed = session.drainFrameResults().lastOrNull()
        if (completed != null) attachment.demandPending = false
        // Hosts re-arm on their next pass, after any consumer has released its frame.
        if (!attachment.demandPending && completed == null) {
          attachment.demandPending = true
          // A surface target shows each frame it draws.
          session.demandFrame(FrameDemand(flags = FrameDemandFlag.WAIT_FOR_UPDATE))
        }
        completed ?: return MlnFfiFrameResult.AwaitUpdate
      } catch (error: MaplibreException) {
        // A failed session rejects new demands with its failure status.
        throw sessionFailed(session, error)
      }
    when (result.disposition) {
      RenderResult.RENDERED -> {
        // Replacement retires unacquired textures; a queued result can still describe the old one.
        if (result.generation != session.getSnapshot().generation)
          return MlnFfiFrameResult.RetryNextFrame
      }
      RenderResult.FAILED -> throw sessionFailed(session, null)
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
          acquired.release(GpuSync(GpuSyncKind.CPU_COMPLETE))
          throw error
        }
        frame.releaseAfterReads = { consumerCompleted ->
          if (!consumerCompleted)
            attachment.host.withRendererAccess {
              if (attachment.session === session) session.abandon()
            }
          // The host's reads have finished on the CPU timeline by now.
          acquired.release(GpuSync(GpuSyncKind.CPU_COMPLETE))
        }
        attachment.host.requestFrame()
      }
    } catch (error: MaplibreException) {
      throw MlnFfiRecoverableFrameException("The MapLibre render session failed", error)
    }
    return presented(attachment, frame, presentedView, captureProjection)
  }

  /**
   * Renderer thread. Takes the frames that a pacer or the host's redraw demanded, and demands one
   * when the redraw wake asked for it.
   */
  private fun takeDemandedFrames(
    attachment: RendererAttachment,
    session: RenderSessionHandle,
    demands: MlnFfiFrameDemands,
    frame: MlnFfiMapFrame,
    captureProjection: Boolean,
  ): MlnFfiFrameResult {
    val rendered =
      try {
        if (demands is MlnFfiFrameDemands.Paced && attachment.pacer !== demands.pacer) {
          session.setFramePacer(demands.pacer)
          attachment.pacer = demands.pacer
        }
        if (attachment.redrawRequested.exchange(false)) session.demandFrame(FrameDemand())
        val results = session.drainFrameResults()
        if (results.any { it.disposition == RenderResult.FAILED })
          throw sessionFailed(session, null)
        val latest =
          results.lastOrNull { it.disposition == RenderResult.RENDERED }
            ?: return MlnFfiFrameResult.AwaitUpdate
        // A frame from before the session's last retarget showed the previous window.
        if (latest.generation != session.getSnapshot().generation)
          return MlnFfiFrameResult.AwaitUpdate
        latest
      } catch (error: MaplibreException) {
        throw sessionFailed(session, error)
      }
    return presented(attachment, frame, rendered.view, captureProjection)
  }

  private fun presented(
    attachment: RendererAttachment,
    frame: MlnFfiMapFrame,
    presentedView: MapView,
    captureProjection: Boolean,
  ): MlnFfiFrameResult {
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
      !attachment.targetReleased &&
        attached?.generation == frame.target.generation &&
        attached.extent == extent &&
        live != null
    )
      return true

    attachment.ready = false
    // A renderer compiles its shaders for one pixel ratio, so a scale-factor change needs a new
    // one.
    val retargeted =
      live != null &&
        attached != null &&
        attached.extent.scaleFactor == extent.scaleFactor &&
        retarget(
          attachment,
          live,
          previous = attached.takeUnless { attachment.targetReleased },
          target = frame.target,
          extent = extent,
        )
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
      // The session renders with the graphics context that it creates for the window.
      is PlatformWindowTarget -> RenderDriverKind.CORE_WORKER
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
    val demands = attachment.host.frameDemands
    val options = attachment.attachOptions(driver, demands)
    val started: RenderSessionHandle =
      when (target) {
        is OpenGlTextureTarget -> {
          target.makeContextCurrent()
          map.attachOpenGlBorrowedTexture(target.toDescriptor(extent), options)
        }
        is OpenGlSurfaceTarget -> map.attachOpenGlSurface(target.toDescriptor(extent), options)
        else -> attachPlatformTarget(map, target, extent, options)
      }
    attachment.session = started
    attachment.driver = driver
    attachment.target = target
    try {
      if (demands is MlnFfiFrameDemands.Paced) {
        started.setFramePacer(demands.pacer)
        attachment.pacer = demands.pacer
      }
      attachment.await(started, started.ready)
      attachment.acquiresFrames =
        RenderSessionCapabilityFlag.FRAME_ACQUISITION in started.getCapabilities().flags
    } catch (error: Throwable) {
      // A failed attachment still needs detach or abandon before the session can retire.
      runCatching { attachment.closeSession() }.exceptionOrNull()?.let(error::addSuppressed)
      throw error
    }
  }

  /**
   * Whether [session] took the replacement; a refusal leaves it rendering into its old target.
   * [previous] is the target that the session renders into, or null after [releaseTarget].
   */
  private fun retarget(
    attachment: RendererAttachment,
    session: RenderSessionHandle,
    previous: MlnFfiRenderTarget?,
    target: MlnFfiRenderTarget,
    extent: MapExtent,
  ): Boolean {
    try {
      val replacement: Completion<*>? =
        when (target) {
          is OpenGlTextureTarget -> {
            target.makeContextCurrent()
            session.setOpenGlBorrowedTextureTarget(target.toReplacement(extent))
          }
          is OpenGlSurfaceTarget -> session.setOpenGlSurfaceTarget(target.toReplacement(extent))
          is PlatformWindowTarget ->
            if (previous is PlatformWindowTarget && previous.window === target.window) {
              // The session resizes its window's surface and the map in order with its frames.
              session.discarding.resize(extent.toLogicalSize())
              // The resized window has no image yet, but a Vulkan session has nothing new to draw
              // until the map changes, so a pacer would leave the window black.
              session.demandFrame(FrameDemand(flags = FrameDemandFlag.FORCE))
              null
            } else replacePlatformTarget(session, target, extent)
          else -> replacePlatformTarget(session, target, extent)
        }
      replacement?.let { attachment.await(session, it) }
      attachment.targetReleased = false
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

  /**
   * Why [session] failed, for the host's recovery. A session that failed for any reason accepts
   * nothing but its release, so recovery attaches a new one, which the host bounds in attempts.
   */
  private fun sessionFailed(
    session: RenderSessionHandle,
    cause: MaplibreException?,
  ): MlnFfiRecoverableFrameException {
    val status = runCatching { session.getSnapshot().failureStatus }.getOrNull()
    val reason = runCatching { session.getFailure() }.getOrNull()?.takeIf { it.isNotBlank() }
    return MlnFfiRecoverableFrameException(
      "The MapLibre render session failed (${status ?: cause?.status}): " +
        (reason ?: cause?.message ?: "no reason given"),
      cause,
    )
  }

  private fun OpenGlTextureTarget.toDescriptor(extent: MapExtent) =
    OpenGlBorrowedTextureDescriptor(
      logicalSize = extent.toLogicalSize(),
      physicalWidth = extent.physicalWidth.coerceAtLeast(1).toUInt(),
      physicalHeight = extent.physicalHeight.coerceAtLeast(1).toUInt(),
      context = context.toFfi(),
      textures = listOf(OpenGlBorrowedTexture(textureName.toUInt())),
      target = textureTarget.toUInt(),
    )

  private fun OpenGlTextureTarget.toReplacement(extent: MapExtent) =
    OpenGlBorrowedTextureReplacement(
      logicalSize = extent.toLogicalSize(),
      physicalWidth = extent.physicalWidth.coerceAtLeast(1).toUInt(),
      physicalHeight = extent.physicalHeight.coerceAtLeast(1).toUInt(),
      textures = listOf(OpenGlBorrowedTexture(textureName.toUInt())),
    )

  private fun OpenGlSurfaceTarget.toDescriptor(extent: MapExtent) =
    OpenGlSurfaceDescriptor(
      logicalSize = extent.toLogicalSize(),
      context = context.toFfi(),
      surface = NativePointer.ofAddress(surface.address),
    )

  private fun OpenGlSurfaceTarget.toReplacement(extent: MapExtent) =
    OpenGlSurfaceReplacement(
      logicalSize = extent.toLogicalSize(),
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

internal fun OpenGlContextHandles.toFfi(): OpenGlContextDescriptor =
  when (this) {
    is EglContextHandles -> toFfi()
    is WglContextHandles -> toFfi()
  }

internal fun EglContextHandles.toFfi(): OpenGlContextDescriptor {
  val dedicated = ownership == OpenGlContextOwnership.DEDICATED
  return OpenGlContextDescriptor(
    ownership = ownership,
    data =
      OpenGlContextDescriptorData.Egl(
        EglContextDescriptor(
          display = NativePointer.ofAddress(display.address),
          config = NativePointer.ofAddress(config.address),
          shareContext =
            if (dedicated) NativePointer.NULL_POINTER
            else NativePointer.ofAddress(shareContext.address),
          clientApi =
            if (dedicated && clientApi == OpenGlClientApi.UNSPECIFIED) OpenGlClientApi.GLES
            else clientApi,
          getProcAddress = NativePointer.ofAddress(getProcAddress.address),
        ),
      ),
  )
}

internal fun WglContextHandles.toFfi(): OpenGlContextDescriptor =
  OpenGlContextDescriptor(
    ownership = ownership,
    data =
      OpenGlContextDescriptorData.Wgl(
        WglContextDescriptor(
          deviceContext = NativePointer.ofAddress(deviceContext.address),
          shareContext =
            if (ownership == OpenGlContextOwnership.DEDICATED) NativePointer.NULL_POINTER
            else NativePointer.ofAddress(shareContext.address),
          getProcAddress = NativePointer.ofAddress(getProcAddress.address),
        ),
      ),
  )
