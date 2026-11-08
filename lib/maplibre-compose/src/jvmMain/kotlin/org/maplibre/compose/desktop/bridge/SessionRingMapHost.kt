package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.DesktopComposeMapPresentationHost
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.nativeffi.AcquiredFrameHandle

/** How many textures a session-allocated ring has: one Compose draws, one to render, one spare. */
internal const val SessionRingDepth = 3

/**
 * A desktop host for a ring of textures that MapLibre's session allocates on the device Compose
 * draws with, a device of type [D]. The session renders the next frame into one texture while
 * Compose draws the newest, so rendering overlaps Compose's reads.
 *
 * Each acquired frame names its texture, which the host opens once for Compose as a [T] and draws
 * with [presenter]. The session sizes its ring at the map's logical size times its scale factor,
 * rounded up, so a texture can be a pixel larger than the map; the presenter crops that pixel.
 */
internal abstract class SessionRingMapHost<C : ComposeGpuContext, D : Any, T : Any>(
  presentationHost: DesktopComposeMapPresentationHost<C>,
  backends: RenderBackendPair,
  rendererThreadName: String,
  private val presenter: SkiaTexturePresenter<T>,
  deviceChangeMessage: String,
) : DesktopMapHost<C>(presentationHost, backends, rendererThreadName) {
  private val deviceChange = DeviceChangeRecovery<D>(deviceChangeMessage)

  /** Compose's views of the ring's textures. Accessed with GPU access, like every field below. */
  protected val ringTextures =
    RingTextures<T> {
      presenter.forgetTexture(it)
      dispose(it)
    }

  private var device: D? = null
  private var ring: MlnFfiRenderTarget? = null
  private var generation = 0L

  /** Compose's view of the newest acquired frame, which [draw] presents. */
  private var newest: T? = null

  /** The device Compose draws with in [context]. The ring must belong to it. */
  protected abstract fun deviceOf(context: C): D

  /**
   * The ring target for [extent] on [device]. Runs with GPU access; producer state such as a Vulkan
   * device is created on the renderer thread and closed in [closeProducers].
   */
  protected abstract fun ringTarget(
    device: D,
    extent: MapExtent,
    generation: Long,
  ): MlnFfiRenderTarget

  /**
   * Compose's view of [frame]'s texture, from [ringTextures] when the host opened it before. Runs
   * with GPU access, while the frame is acquired.
   */
  protected abstract fun openFrame(context: C, frame: AcquiredFrameHandle): T

  /** Frees [texture] after the presenter dropped its Skia wrapper. Runs with GPU access. */
  protected abstract fun dispose(texture: T)

  override fun acquireFrame(extent: MapExtent): MlnFfiMapFrameAcquisition =
    withComposeContext { context ->
      val nextDevice = deviceOf(context)
      val current = ring
      // A resize needs every frame released; otherwise only those Compose no longer reads.
      val unchanged = current != null && current.extent == extent && device == nextDevice
      frameCompletion.prepare(context.skiaContext, keepNewest = unchanged, ::contextReplaced)
      if (deviceChange.changed(device, nextDevice)) {
        // Recovery closed the session that rendered on the old device.
        newest = null
        ringTextures.releaseAll()
        presenter.closeAll()
        rendererThread.run(::closeProducers)
        ring = null
      }
      device = nextDevice
      val target =
        ring?.takeIf { it.extent == extent }
          ?: run {
            // The session allocates new textures at the new size, whose handles may repeat old
            // ones.
            ringTextures.retireAll()
            if (ring == null) generation++
            ringTarget(nextDevice, extent, generation).also { ring = it }
          }
      MlnFfiMapFrameAcquisition.Acquired(MlnFfiMapFrame(target))
    } ?: MlnFfiMapFrameAcquisition.NotReady

  override fun completeProducerAccess(frame: MlnFfiMapFrame) {
    val acquired = frame.acquiredFrame
    presentationHost.onGpuThread {
      holdFrame(frame)
      if (acquired != null) withComposeContext { context -> newest = openFrame(context, acquired) }
    }
  }

  final override fun draw(
    scope: DrawScope,
    target: MlnFfiRenderTarget,
    destination: MlnFfiMapDestination,
  ): Boolean =
    withComposeContext { context ->
      frameCompletion.adoptContext(context.skiaContext, ::contextReplaced)
      val texture = newest
      // A resize keeps the generation; only a new device replaces the ring.
      if (texture == null || target.backend != producer || target.generation != generation) {
        return@withComposeContext false
      }
      val drew = presenter.draw(scope, context.skiaContext, texture, destination, frameCompletion)
      if (drew) ringTextures.releaseRetired(except = texture)
      drew
    } ?: false

  /**
   * The session's handles close with it, and a replacement session can reuse their values for new
   * textures, so views opened from them must not be found again.
   */
  override fun consumerAccessCompleted() {
    ringTextures.retireAll()
  }

  /** The ring's Skia wrappers belong to the replaced context; the textures stay with the device. */
  override fun contextReplaced() {
    presenter.closeAll()
  }

  override fun closeTextures() {
    presentationHost.runOnGpuThread {
      newest = null
      ringTextures.releaseAll()
      presenter.closeAll()
    }
  }
}

/**
 * Compose's views of the textures in a session's ring, by slot.
 *
 * A slot keeps its view until a frame in that slot names another texture, or [retireAll] retires
 * every view. A retired view stays until Compose has drawn another one, since Compose may still
 * draw the frame it shows.
 */
internal class RingTextures<T : Any>(private val release: (T) -> Unit) {
  private class Slot<T>(val texture: Long, val view: T)

  private val slots = mutableMapOf<Int, Slot<T>>()
  private val retired = mutableListOf<T>()

  /** The view of the texture [texture] names in [slot], opening it with [open] the first time. */
  fun getOrOpen(slot: Int, texture: Long, open: () -> T): T {
    val current = slots[slot]
    if (current != null && current.texture == texture) return current.view
    val view = open()
    slots.remove(slot)?.let { retired += it.view }
    slots[slot] = Slot(texture, view)
    return view
  }

  /** Retires every view, as before the session replaces its textures. */
  fun retireAll() {
    slots.values.mapTo(retired) { it.view }
    slots.clear()
  }

  /** Releases every retired view except [except], which Compose just drew. */
  fun releaseRetired(except: T) {
    val iterator = retired.iterator()
    while (iterator.hasNext()) {
      val view = iterator.next()
      if (view != except) {
        release(view)
        iterator.remove()
      }
    }
  }

  /**
   * Releases every view. Each stays until its release returns, so after a failure a later call
   * retries the ones not yet released.
   */
  fun releaseAll() {
    retireAll()
    while (retired.isNotEmpty()) {
      release(retired.first())
      retired.removeFirst()
    }
  }
}
