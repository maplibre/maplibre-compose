package org.maplibre.compose.desktop.bridge

import androidx.compose.ui.graphics.drawscope.DrawScope
import java.util.concurrent.ConcurrentLinkedQueue
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
import org.maplibre.compose.mlnffi.MlnFfiRenderTarget
import org.maplibre.compose.mlnffi.NativeHandle
import org.maplibre.compose.mlnffi.RenderBackendPair
import org.maplibre.compose.mlnffi.TextureOrigin

/** All map producers share Metal allocation, presentation, and frame ownership. */
internal class MetalMapHost(
  presentationHost: ComposeMapPresentationHost,
  producer: MapRenderBackend = MapRenderBackend.Metal,
) :
  SharedTextureMapHost<MetalComposeGpuContext, MetalMapHost.SharedTexture>(
    presentationHost,
    RenderBackendPair(producer, ComposeRenderBackend.Metal),
    "maplibre-metal-host-renderer",
  ) {
  private val presenter = SkiaTexturePresenter(MetalTextureWrapper)

  /** `MTLTexture`s from [release], waiting for the GPU thread to drop their Skia wrappers. */
  private val pendingMetalDisposals = ConcurrentLinkedQueue<Long>()
  private val deviceChange =
    DeviceChangeRecovery<NativeHandle>("Compose changed Metal devices; recreating the map renderer")
  private var device = NativeHandle(0)
  private var vulkan: VulkanDevice? = null
  private var angle: DesktopEglContext? = null

  override fun acquireFrame(extent: MapExtent): MlnFfiMapFrameAcquisition =
    withPreparedContext { context ->
      if (deviceChange.changed(device.takeUnless { it.isNull }, context.device)) {
        // Recovery closes the FFI session before we release its borrowed device and images.
        textures.releaseAll()
        rendererThread.run(::closeProducers)
      }
      device = context.device
      val previous = textures.current
      if (previous == null || previous.presentation.extent != extent) {
        val nextGeneration = textures.generation + 1
        textures.replaceCurrent(rendererThread.run { allocate(extent, nextGeneration) })
      }
      MlnFfiMapFrameAcquisition.Acquired(MlnFfiMapFrame(checkNotNull(textures.current).target))
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
        if (producer == MapRenderBackend.OpenGl) TextureOrigin.BottomLeft
        else TextureOrigin.TopLeft,
        extent,
        generation,
      )
    try {
      return when (producer) {
        MapRenderBackend.Metal -> SharedTexture(presentation, presentation) {}
        MapRenderBackend.Vulkan -> {
          val context = vulkan ?: VulkanDevice.forMetalDevice(device.address).also { vulkan = it }
          val imported = context.importMetalTexture(texture, extent)
          SharedTexture(imported.target(generation), presentation, imported::close)
        }
        MapRenderBackend.OpenGl -> {
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

  override fun <R> onRendererThread(action: () -> R): R = ObjectiveC.runInAutoreleasePool(action)

  override fun waitForProducers() {
    vulkan?.waitIdle()
    angle?.waitIdle()
  }

  override fun present(
    scope: DrawScope,
    context: MetalComposeGpuContext,
    texture: SharedTexture,
    generation: Long,
    destination: MlnFfiMapDestination,
  ): Boolean {
    disposePendingMetalTextures(keepAlive = texture.presentation.texture.address)
    return presenter.draw(
      scope,
      context.skiaContext,
      texture.presentation,
      destination,
      frameCompletion,
    )
  }

  /** Hands a texture back once nothing will render into it again. Safe from any thread. */
  override fun release(texture: SharedTexture) {
    rendererThread.run(texture.closeProducer)
    val metalTexture = texture.presentation.texture
    if (!metalTexture.isNull) pendingMetalDisposals.add(metalTexture.address)
  }

  /**
   * Frees pending textures, except one the caller is about to draw: a texture handed back inside
   * `acquireFrame` can be presented again in the same frame, and freeing it early makes
   * `BackendRenderTarget.makeMetal` `CFRetain` a released `MTLTexture` and trap. Runs with the
   * host's exclusive GPU access.
   */
  private fun disposePendingMetalTextures(keepAlive: Long) {
    if (pendingMetalDisposals.isEmpty()) return
    var deferred: Long? = null
    while (true) {
      val address = pendingMetalDisposals.poll() ?: break
      if (address == keepAlive) {
        deferred = address
        continue
      }
      // Order matters: Skia holds a surface wrapping this texture, so that has to go first.
      presenter.forget(address)
      MetalTexture.dispose(address)
    }
    deferred?.let(pendingMetalDisposals::add)
  }

  override fun <R> withComposeContext(action: (MetalComposeGpuContext) -> R): R? =
    presentationHost.onGpuThread {
      val context = presentationHost.gpuContext() ?: return@onGpuThread null
      check(context is MetalComposeGpuContext) { "The host no longer reports a Metal context" }
      action(context)
    }

  override fun contextReplaced() {
    presenter.closeAll()
  }

  override fun closeTextures() {
    textures.releaseAll()
    presentationHost.runOnGpuThread {
      disposePendingMetalTextures(keepAlive = 0L)
      presenter.closeAll()
    }
  }

  override fun closeProducers() {
    vulkan?.close()
    vulkan = null
    angle?.close()
    angle = null
  }

  internal class SharedTexture(
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
