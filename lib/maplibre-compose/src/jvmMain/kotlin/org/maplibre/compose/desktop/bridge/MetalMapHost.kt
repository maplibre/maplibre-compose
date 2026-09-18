package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import org.maplibre.compose.desktop.ComposeMapPresentationHost
import org.maplibre.compose.desktop.MetalComposeGpuContext
import org.maplibre.compose.desktop.onGpuThread
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.mlnffi.ComposeRenderBackend
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MetalTextureTarget
import org.maplibre.compose.mlnffi.MlnFfiHostException
import org.maplibre.compose.mlnffi.MlnFfiMapDestination
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapFrameAcquisition
import org.maplibre.compose.mlnffi.MlnFfiMapHost
import org.maplibre.compose.mlnffi.MlnFfiRecoverableFrameException
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.TextureOrigin

/** All map producers share Metal allocation, presentation, and frame ownership. */
internal class MetalMapHost(
  private val presentationHost: ComposeMapPresentationHost,
  private val producer: MapRenderBackend = MapRenderBackend.METAL,
) : MlnFfiMapHost {
  private val rendererThread = MapRendererThread("maplibre-metal-host-renderer")
  private val presenter = MetalPresenter(presentationHost)
  private val frameCompletion = ComposeFrameCompletion()
  private val textures = mutableMapOf<Long, SharedTexture>()
  private var generation = 0L
  private var device = NativeHandle(0)
  private var pendingDevice: NativeHandle? = null
  private var vulkan: MacVulkanContext? = null
  private var angle: DesktopEglContext? = null

  override val backends = RenderBackendPair(producer, ComposeRenderBackend.METAL)

  override fun acquireFrame(
    frameId: Long,
    extent: MapExtent,
    presentationTimeNanos: Long?,
  ): MlnFfiMapFrameAcquisition =
    withPreparedContext { context ->
      if (!device.isNull && device != context.device) {
        if (pendingDevice != context.device) {
          pendingDevice = context.device
          throw MlnFfiRecoverableFrameException(
            "Compose changed Metal devices; recreating the map renderer",
            null,
          )
        }
        // Recovery closes the FFI session before we release its borrowed device and images.
        disposeTextures()
        rendererThread.run {
          vulkan?.close()
          vulkan = null
          angle?.close()
          angle = null
        }
      }
      pendingDevice = null
      device = context.device
      val previous = textures[generation]
      if (previous == null || previous.presentation.extent != extent) {
        val nextGeneration = generation + 1
        textures[nextGeneration] = rendererThread.run { allocate(extent, nextGeneration) }
        generation = nextGeneration
      }
      MlnFfiMapFrameAcquisition.Acquired(
        MlnFfiMapFrame(frameId, extent, textures.getValue(generation).target, presentationTimeNanos)
      )
    } ?: MlnFfiMapFrameAcquisition.NotReady

  private fun allocate(extent: MapExtent, generation: Long): SharedTexture {
    val texture =
      NativeHandle(
        MetalTexture.create(device.address, 0, extent.physicalWidth, extent.physicalHeight)
      )
    val presentation =
      MetalTextureTarget(
        texture,
        MetalTexture.pixelFormat(texture.address),
        if (producer == MapRenderBackend.OPENGL) TextureOrigin.BOTTOM_LEFT
        else TextureOrigin.TOP_LEFT,
        extent,
        generation,
      )
    try {
      return when (producer) {
        MapRenderBackend.METAL -> SharedTexture(presentation, presentation) {}
        MapRenderBackend.VULKAN -> {
          val context = vulkan ?: MacVulkanContext.create(device.address).also { vulkan = it }
          val imported = context.createImportedTexture(texture, extent)
          SharedTexture(imported.target(generation), presentation, imported::close)
        }
        MapRenderBackend.OPENGL -> {
          val context = angle ?: DesktopEglContext.create(device.address).also { angle = it }
          val imported = context.createImportedTexture(texture, extent)
          SharedTexture(imported.target(generation), presentation, imported::close)
        }
      }
    } catch (error: Throwable) {
      MetalTexture.dispose(texture.address)
      throw error
    }
  }

  override fun <T> withProducerAccess(frame: MlnFfiMapFrame, action: () -> T): T =
    withRendererAccess(action)

  override fun <T> withRendererAccess(action: () -> T): T = rendererThread.run {
    ObjectiveC.runInAutoreleasePool(action)
  }

  override fun enqueueRenderer(action: () -> Unit): Boolean = rendererThread.post {
    ObjectiveC.runInAutoreleasePool(action)
  }

  override fun completeProducerAccess(frame: MlnFfiMapFrame) {
    rendererThread.run {
      vulkan?.waitIdle()
      angle?.waitIdle()
    }
  }

  override fun draw(
    scope: DrawScope,
    target: MlnFfiRenderTarget,
    destination: MlnFfiMapDestination,
  ): Boolean =
    withPreparedContext { context ->
      val texture = textures[target.generation] ?: return@withPreparedContext false
      val drew =
        presenter.draw(
          scope,
          context.skiaContext,
          texture.presentation,
          destination,
          frameCompletion,
        )
      if (drew) {
        val retired = textures.keys.filter { it != generation && it != target.generation }
        retired.forEach { retire(textures.remove(it)!!) }
      }
      drew
    } ?: false

  private fun retire(texture: SharedTexture) {
    rendererThread.run(texture.closeProducer)
    presenter.retire(texture.presentation.texture)
  }

  private fun disposeTextures() {
    textures.values.forEach(::retire)
    textures.clear()
  }

  private fun <T> withPreparedContext(action: (MetalComposeGpuContext) -> T): T? =
    presentationHost.onGpuThread {
      val context = presentationHost.gpuContext() ?: return@onGpuThread null
      check(context is MetalComposeGpuContext) { "The host no longer reports a Metal context" }
      frameCompletion.prepare(context.skiaContext, presenter::resetContext)
      action(context)
    }

  override fun close() {
    try {
      frameCompletion.abandon()
      disposeTextures()
      // The presenter acquires the host's GPU access once to release the Skia wrappers.
      presenter.close()
    } finally {
      try {
        rendererThread.run {
          vulkan?.close()
          angle?.close()
        }
      } finally {
        rendererThread.close()
      }
    }
  }

  private class SharedTexture(
    val target: MlnFfiRenderTarget,
    val presentation: MetalTextureTarget,
    val closeProducer: () -> Unit,
  )
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
