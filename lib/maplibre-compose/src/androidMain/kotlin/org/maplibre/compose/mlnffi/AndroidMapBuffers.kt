package org.maplibre.compose.mlnffi

import android.hardware.HardwareBuffer
import android.view.SurfaceControl
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.maplibre.compose.map.MapExtent

/**
 * Hardware buffers that MapLibre renders into as a borrowed texture ring, and that SurfaceFlinger
 * displays. Three slots leave room for a buffer on screen, one waiting for the window draw, and one
 * being rendered; the render session parks its demands while every slot is held.
 *
 * Render-thread state. Frames in flight return their slots from any thread.
 */
@RequiresApi(33)
internal abstract class AndroidMapBuffers : AndroidMapGraphicsContext {
  /** The textures imported from one ring's buffers. */
  protected interface ImportedRing : AutoCloseable {
    val target: MlnFfiRenderTarget
  }

  /** How SurfaceFlinger transforms a buffer to show the map upright. */
  abstract val bufferTransform: Int

  /** Imports [buffers] as the backend's textures, in slot order. */
  protected abstract fun import(
    buffers: List<HardwareBuffer>,
    extent: MapExtent,
    generation: Long,
  ): ImportedRing

  /** Releases the backend context after every ring is closed. */
  protected abstract fun closeContext()

  private inner class Ring(
    val extent: MapExtent,
    val generation: Long,
    private val buffers: List<AndroidMapBuffer>,
    val imported: ImportedRing,
  ) : AutoCloseable {
    private val inFlight = ConcurrentHashMap.newKeySet<AndroidMapBufferFrame>()

    fun frame(
      frame: MlnFfiMapFrame,
      projection: MlnFfiMapFrameProjection,
      host: MlnFfiMapHostSession,
    ): AndroidMapBufferFrame {
      val buffer = buffers[frame.acquiredSlot]
      buffer.retain()
      return AndroidMapBufferFrame(buffer, projection, bufferTransform, frame, host, inFlight)
        .also {
          inFlight += it
        }
    }

    fun retireFrames() {
      inFlight.forEach(AndroidMapBufferFrame::retire)
    }

    override fun close() {
      try {
        imported.close()
      } finally {
        buffers.forEach(AndroidMapBuffer::release)
      }
    }
  }

  private var current: Ring? = null
  /** Rings the session may still render into until it renders into [current]. */
  private val retired = mutableListOf<Ring>()
  private var nextGeneration = 1L

  /** Ignores [generation]: a ring has its own, which changes whenever its buffers do. */
  override fun target(extent: MapExtent, generation: Long): MlnFfiRenderTarget {
    current
      ?.takeIf { it.extent == extent }
      ?.let {
        return it.imported.target
      }
    retire()
    return allocate(extent).also { current = it }.imported.target
  }

  /**
   * Discards the frames in flight and retires their ring, so the next target has new buffers. A
   * session never renders into a buffer that SurfaceFlinger may still show.
   */
  fun retire() {
    val ring = current ?: return
    current = null
    retired += ring
    ring.retireFrames()
  }

  /**
   * Takes over a rendered frame and its projection. Returns null after discarding a frame of a
   * retired ring.
   */
  fun frame(
    frame: MlnFfiMapFrame,
    projection: MlnFfiMapFrameProjection,
    host: MlnFfiMapHostSession,
  ): AndroidMapBufferFrame? {
    val ring = current?.takeIf { it.generation == frame.target.generation }
    if (ring == null) {
      projection.close()
      checkNotNull(frame.discardAfterReads)()
      return null
    }
    // The session renders into this ring, so it no longer uses any retired one.
    closeRetired()
    return ring.frame(frame, projection, host)
  }

  override fun close() {
    retire()
    try {
      closeRetired()
    } finally {
      closeContext()
    }
  }

  private fun closeRetired() {
    while (retired.isNotEmpty()) retired.removeAt(0).close()
  }

  private fun allocate(extent: MapExtent): Ring {
    val generation = nextGeneration++
    val buffers = mutableListOf<AndroidMapBuffer>()
    try {
      repeat(RingDepth) {
        buffers +=
          AndroidMapBuffer(
            HardwareBuffer.create(
              extent.physicalWidth,
              extent.physicalHeight,
              HardwareBuffer.RGBA_8888,
              1,
              BufferUsage,
            ),
          )
      }
      val imported = import(buffers.map { it.hardwareBuffer }, extent, generation)
      return Ring(extent, generation, buffers, imported)
    } catch (error: Throwable) {
      buffers.forEach(AndroidMapBuffer::release)
      throw error
    }
  }

  companion object {
    private const val RingDepth = 3

    private const val BufferUsage =
      HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
        HardwareBuffer.USAGE_COMPOSER_OVERLAY

    /** Whether the device allocates map buffers at [extent]. */
    fun isSupported(extent: MapExtent): Boolean =
      HardwareBuffer.isSupported(
        extent.physicalWidth,
        extent.physicalHeight,
        HardwareBuffer.RGBA_8888,
        1,
        BufferUsage,
      )

    fun create(backend: MapRenderBackend): AndroidMapBuffers =
      when (backend) {
        MapRenderBackend.OpenGl -> AndroidEglMapBuffers.create()
        MapRenderBackend.Vulkan -> AndroidVulkanMapBuffers.create()
        MapRenderBackend.Metal -> error("Metal is not an Android render backend")
      }
  }
}

/** A hardware buffer shared by its ring and the frames on their way to SurfaceFlinger. */
@RequiresApi(33)
internal class AndroidMapBuffer(val hardwareBuffer: HardwareBuffer) {
  private val references = AtomicInteger(1)

  fun retain() {
    references.incrementAndGet()
  }

  fun release() {
    if (references.decrementAndGet() == 0) hardwareBuffer.close()
  }
}

/**
 * A rendered ring slot on its way to SurfaceFlinger, with the projection it was rendered with. The
 * slot returns to the render session once: released after SurfaceFlinger's last read, or discarded
 * when its ring retires first.
 */
@RequiresApi(33)
internal class AndroidMapBufferFrame(
  private val buffer: AndroidMapBuffer,
  val projection: MlnFfiMapFrameProjection,
  private val transform: Int,
  private val native: MlnFfiMapFrame,
  private val host: MlnFfiMapHostSession,
  private val inFlight: MutableSet<AndroidMapBufferFrame>,
) {
  private val returned = AtomicBoolean()

  /**
   * UI thread. Shows the buffer on [control] when [transaction] applies. Call once, and not after
   * [discard]; the projection stays with the caller.
   */
  fun setBuffer(transaction: SurfaceControl.Transaction, control: SurfaceControl) {
    // The render session completed its GPU work before the frame was acquired.
    // TODO(https://github.com/maplibre/maplibre-native-ffi/issues/1374): pass the session's
    // producer fence instead.
    transaction
      .setBuffer(control, buffer.hardwareBuffer, null) { fence ->
        ReleaseFenceWaiter.execute {
          // TODO(https://github.com/maplibre/maplibre-native-ffi/issues/1374): release the frame
          // with SurfaceFlinger's fence instead of waiting for it here.
          fence.use { it.awaitForever() }
          release()
        }
      }
      .setBufferTransform(control, transform)
    // The transaction holds its own reference.
    buffer.release()
  }

  /** UI thread. Returns a frame that never reached SurfaceFlinger, and closes its projection. */
  fun discard() {
    try {
      projection.close()
    } finally {
      buffer.release()
      release()
    }
  }

  /** Gives up the slot while SurfaceFlinger may still read it, because its ring retired. */
  fun retire() {
    if (claim()) checkNotNull(native.discardAfterReads)()
  }

  private fun release() {
    // A host that rejects work has already retired the ring that holds this frame.
    host.enqueueRenderer { if (claim()) checkNotNull(native.releaseAfterReads)(true) }
  }

  private fun claim(): Boolean {
    if (!returned.compareAndSet(false, true)) return false
    inFlight -= this
    return true
  }

  private companion object {
    /** Waits for SurfaceFlinger's reads off the render thread, which must keep servicing work. */
    val ReleaseFenceWaiter: Executor by lazy {
      Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "maplibre-compose-buffer-release").apply { isDaemon = true }
      }
    }
  }
}
