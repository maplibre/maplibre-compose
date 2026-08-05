package org.maplibre.compose.glfw

/**
 * The `MTLTexture` MapLibre renders into, allocated by hand through Objective-C.
 *
 * Every entry point opens an autorelease pool: Metal's factory methods return autoreleased objects,
 * and these are called from a thread that has no pool of its own.
 */
internal object GlfwMetalTexture {
  private const val MTL_TEXTURE_TYPE_2D = 2L
  private const val MTL_TEXTURE_USAGE_SHADER_READ = 1L
  private const val MTL_TEXTURE_USAGE_RENDER_TARGET = 4L
  private const val MTL_STORAGE_MODE_PRIVATE = 2L

  /** `MTLPixelFormatBGRA8Unorm`, which is what a `CAMetalLayer` presents and what Skia expects. */
  const val MTL_PIXEL_FORMAT_BGRA8_UNORM: Long = 80L

  /**
   * Allocates a texture of [width] by [height] physical pixels, reusing [oldTexture] when it
   * already has that size. The returned address is owned by the caller unless it is [oldTexture].
   */
  fun create(device: Long, oldTexture: Long, width: Int, height: Int): Long =
    GlfwObjectiveC.autoreleasePool().use {
      if (oldTexture != 0L) {
        val oldWidth = GlfwObjectiveC.sendLong(oldTexture, "width")
        val oldHeight = GlfwObjectiveC.sendLong(oldTexture, "height")
        if (oldWidth == width.toLong() && oldHeight == height.toLong()) return oldTexture
      }

      val descriptor = GlfwObjectiveC.allocInit("MTLTextureDescriptor")
      try {
        GlfwObjectiveC.sendVoid(descriptor, "setTextureType:", MTL_TEXTURE_TYPE_2D)
        GlfwObjectiveC.sendVoid(descriptor, "setPixelFormat:", MTL_PIXEL_FORMAT_BGRA8_UNORM)
        GlfwObjectiveC.sendVoid(descriptor, "setWidth:", width.toLong())
        GlfwObjectiveC.sendVoid(descriptor, "setHeight:", height.toLong())
        // Rendered into by MapLibre, sampled by Skia; private storage keeps it GPU-only.
        GlfwObjectiveC.sendVoid(
          descriptor,
          "setUsage:",
          MTL_TEXTURE_USAGE_SHADER_READ or MTL_TEXTURE_USAGE_RENDER_TARGET,
        )
        GlfwObjectiveC.sendVoid(descriptor, "setStorageMode:", MTL_STORAGE_MODE_PRIVATE)
        val texture = GlfwObjectiveC.sendPointer(device, "newTextureWithDescriptor:", descriptor)
        check(texture != 0L) { "Metal texture allocation returned null" }
        texture
      } finally {
        GlfwObjectiveC.release(descriptor)
      }
    }

  fun dispose(texture: Long) {
    GlfwObjectiveC.autoreleasePool().use { GlfwObjectiveC.release(texture) }
  }

  fun pixelFormat(texture: Long): Long =
    GlfwObjectiveC.autoreleasePool().use {
      if (texture == 0L) 0L else GlfwObjectiveC.sendLong(texture, "pixelFormat")
    }
}
