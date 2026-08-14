package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.desktop.MetalComposeGpuContext
import org.maplibre.compose.desktop.onGpuThread
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.MlnFfiMapHost
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.TextureOrigin

/** Bridges MapLibre's Metal rendering into a Compose scene drawn with Metal. */
internal class MetalMapHost(private val gpuHost: ComposeMapHost) : MlnFfiMapHost {
  private val rendererThread = MapRendererThread("maplibre-metal-renderer")
  private val presenter = MetalPresenter(gpuHost)
  private val frameCompletion = ComposeFrameCompletion()

  private var texture = NativeHandle(0L)
  private var pixelFormat = 0L
  private var generation = 0L
  private var currentExtent = MapExtent.Empty
  private var currentDevice = NativeHandle(0L)

  override val backends: RenderBackendPair =
    RenderBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL)

  override fun resize(extent: MapExtent) {
    // Reading the context hops to the GPU thread and waits, so it must not happen on the renderer
    // thread, which the GPU thread may itself be waiting on.
    val device = if (extent.isEmpty) null else currentDeviceOrNull() ?: return
    resize(extent, device)
  }

  private fun resize(extent: MapExtent, device: NativeHandle?) {
    // The Skia wrapper around the old texture belongs to the GPU thread, so retire rather than free
    // here; the presenter frees both at the next draw.
    rendererThread.run { resizeOnRendererThread(extent, device) }?.let(presenter::retire)
  }

  override fun acquireFrame(
    frameId: Long,
    extent: MapExtent,
    presentationTimeNanos: Long?,
  ): MlnFfiMapFrameAcquisition {
    val context = withPreparedContext { it } ?: return MlnFfiMapFrameAcquisition.NotReady
    val device = context.device
    if (texture.isNull || extent != currentExtent || device != currentDevice) {
      resize(extent, device)
    }
    return MlnFfiMapFrameAcquisition.Acquired(
      MlnFfiMapFrame(
        frameId = frameId,
        extent = extent,
        target = target(extent, generation),
        presentationTimeNanos = presentationTimeNanos,
      )
    )
  }

  /** The FFI requires `renderUpdate` to run inside an autorelease pool; this thread has none. */
  override fun <T> withProducerAccess(frame: MlnFfiMapFrame, action: () -> T): T =
    rendererThread.run {
      ObjectiveC.runInAutoreleasePool(action)
    }

  override fun <T> withRendererAccess(action: () -> T): T = rendererThread.run(action)

  override fun draw(scope: DrawScope, target: MlnFfiRenderTarget): Boolean {
    if (target !is MetalTextureTarget || target.texture.isNull) return false
    return withPreparedContext { context ->
      presenter.draw(scope, context.skiaContext, target, frameCompletion)
    } ?: false
  }

  override fun close() {
    try {
      frameCompletion.abandon()
      takeTexture()?.let(presenter::retire)
      presenter.close()
    } finally {
      rendererThread.close()
    }
  }

  /**
   * Reallocates the texture for [extent], returning the one it replaced for the caller to retire.
   */
  private fun resizeOnRendererThread(extent: MapExtent, device: NativeHandle?): NativeHandle? {
    if (extent == currentExtent && !texture.isNull && device == currentDevice) return null

    val retiredTexture =
      if (extent.isEmpty) {
        takeTexture()
      } else {
        val gpuDevice =
          checkNotNull(device) { "resize() resolves the Metal device before this hop" }
        val oldTexture = texture
        val reusableTexture = oldTexture.takeIf { gpuDevice == currentDevice } ?: NativeHandle(0L)
        val address =
          MetalTexture.create(
            device = gpuDevice.address,
            oldTexture = reusableTexture.address,
            width = extent.physicalWidth,
            height = extent.physicalHeight,
          )
        texture = NativeHandle(address)
        currentDevice = gpuDevice
        pixelFormat = MetalTexture.pixelFormat(address)
        // create() reuses the old texture when the physical size is unchanged; don't retire it.
        oldTexture.takeIf { !it.isNull && address != it.address }
      }

    currentExtent = extent
    generation += 1
    return retiredTexture
  }

  private fun target(extent: MapExtent, generation: Long): MlnFfiRenderTarget =
    MetalTextureTarget(
      texture =
        texture.takeIf { !it.isNull }
          ?: throw MlnFfiHostException("Metal texture allocation returned null"),
      pixelFormat = pixelFormat,
      origin = TextureOrigin.TOP_LEFT,
      extent = extent,
      generation = generation,
    )

  /** Clears the current texture, returning it for the caller to retire. */
  private fun takeTexture(): NativeHandle? {
    val retiredTexture = texture.takeIf { !it.isNull }
    texture = NativeHandle(0L)
    currentDevice = NativeHandle(0L)
    pixelFormat = 0L
    return retiredTexture
  }

  private fun currentDeviceOrNull(): NativeHandle? {
    return withPreparedContext { it.device }
  }

  private fun <T> withPreparedContext(action: (MetalComposeGpuContext) -> T): T? =
    gpuHost.onGpuThread {
      val context = gpuHost.gpuContext() ?: return@onGpuThread null
      val metalContext =
        context as? MetalComposeGpuContext
          ?: throw MlnFfiHostException(
            "${gpuHost.description} switched from MetalComposeGpuContext to " +
              context::class.simpleName
          )
      frameCompletion.prepare(metalContext.skiaContext, presenter::resetContext)
      action(metalContext)
    }
}

/**
 * The `MTLTexture` MapLibre renders into. Every entry point opens an autorelease pool, since these
 * run on threads that have none of their own.
 */
internal object MetalTexture {
  private const val MTL_TEXTURE_TYPE_2D = 2L
  private const val MTL_PIXEL_FORMAT_BGRA8_UNORM = 80L
  private const val MTL_TEXTURE_USAGE_SHADER_READ = 1L
  private const val MTL_TEXTURE_USAGE_RENDER_TARGET = 4L
  private const val MTL_STORAGE_MODE_PRIVATE = 2L

  /**
   * Allocates a texture of [width] by [height] physical pixels, reusing [oldTexture] if it already
   * has that size. The returned address is owned by the caller unless it is [oldTexture].
   */
  fun create(device: Long, oldTexture: Long, width: Int, height: Int): Long =
    ObjectiveC.autoreleasePool().use {
      if (oldTexture != 0L) {
        val oldWidth = ObjectiveC.sendLong(oldTexture, "width")
        val oldHeight = ObjectiveC.sendLong(oldTexture, "height")
        if (oldWidth == width.toLong() && oldHeight == height.toLong()) {
          return oldTexture
        }
      }

      val descriptor = ObjectiveC.allocInit("MTLTextureDescriptor")
      try {
        ObjectiveC.sendVoid(descriptor, "setTextureType:", MTL_TEXTURE_TYPE_2D)
        ObjectiveC.sendVoid(descriptor, "setPixelFormat:", MTL_PIXEL_FORMAT_BGRA8_UNORM)
        ObjectiveC.sendVoid(descriptor, "setWidth:", width.toLong())
        ObjectiveC.sendVoid(descriptor, "setHeight:", height.toLong())
        ObjectiveC.sendVoid(
          descriptor,
          "setUsage:",
          MTL_TEXTURE_USAGE_SHADER_READ or MTL_TEXTURE_USAGE_RENDER_TARGET,
        )
        ObjectiveC.sendVoid(descriptor, "setStorageMode:", MTL_STORAGE_MODE_PRIVATE)
        val texture = ObjectiveC.sendPointer(device, "newTextureWithDescriptor:", descriptor)
        if (texture == 0L) {
          throw MlnFfiHostException("Metal texture allocation returned null")
        }
        texture
      } finally {
        ObjectiveC.release(descriptor)
      }
    }

  fun dispose(texture: Long) {
    ObjectiveC.autoreleasePool().use { ObjectiveC.release(texture) }
  }

  fun pixelFormat(texture: Long): Long =
    ObjectiveC.autoreleasePool().use {
      if (texture == 0L) 0L else ObjectiveC.sendLong(texture, "pixelFormat")
    }
}
