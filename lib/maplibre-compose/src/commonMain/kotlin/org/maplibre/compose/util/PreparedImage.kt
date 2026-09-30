package org.maplibre.compose.util

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import org.maplibre.compose.map.ResolvedStyleImage
import org.maplibre.compose.sources.ImageSource
import org.maplibre.compose.sources.MutableImageSourceHandle

/**
 * Immutable pixels for [ImageSource], [MutableImageSourceHandle.setImage], and
 * [ResolvedStyleImage]. A prepared image belongs to no map or loaded style, so it can be reused
 * across maps, loaded styles, and image IDs.
 *
 * Equality compares instances, not pixels. Keep and reuse the same instance while an image is
 * unchanged; preparing it again creates a different image and can cause another upload.
 */
@Immutable
public class PreparedImage internal constructor(internal val pixels: EnginePixels) {
  public val width: Int
    get() = pixels.width

  public val height: Int
    get() = pixels.height

  /**
   * Returns a new bitmap on each call; changing it does not change this image. On MapLibre Native,
   * a fully transparent pixel reads back as transparent black.
   */
  public fun toImageBitmap(): ImageBitmap = pixels.toStraightArgb().toImageBitmap(width, height)

  override fun toString(): String = "PreparedImage(${width}x$height)"

  public companion object {
    /**
     * Copies the pixels of [bitmap] on the calling thread, so prepare large images off the main
     * thread. Later changes to [bitmap] do not change the result.
     *
     * @throws IllegalArgumentException if [bitmap] has a zero width or height.
     */
    public fun fromBitmap(bitmap: ImageBitmap): PreparedImage =
      bitmap.readStraightArgb().let { PreparedImage(enginePixels(it.width, it.height, it.argb)) }
  }
}

/**
 * Reads [bitmap] on the calling thread and converts it in [enginePixelsContext]. Style image
 * literals are deduplicated by content, so the content hash is computed there too.
 */
internal suspend fun prepareInEngineContext(bitmap: ImageBitmap): PreparedImage {
  val read = bitmap.readStraightArgb()
  return withContext(enginePixelsContext) {
    PreparedImage(enginePixels(read.width, read.height, read.argb).also { it.hashCode() })
  }
}

private class StraightArgb(val width: Int, val height: Int, val argb: IntArray)

private fun ImageBitmap.readStraightArgb(): StraightArgb {
  require(width > 0 && height > 0) { "A prepared image needs pixels, but got ${width}x$height" }
  val argb = IntArray(width * height)
  readPixels(argb)
  return StraightArgb(width, height, argb)
}

/**
 * Pixels in the form the platform's engine uploads. Equality compares size and content; the hash is
 * computed once.
 */
internal expect class EnginePixels {
  val width: Int
  val height: Int

  /** Returns a new array of straight-alpha ARGB pixels, the format of [ImageBitmap.readPixels]. */
  fun toStraightArgb(): IntArray
}

/** Converts [straightArgb], which the result may take ownership of, to engine pixels. */
internal expect fun enginePixels(width: Int, height: Int, straightArgb: IntArray): EnginePixels

/**
 * Where [prepareInEngineContext] converts pixels: off the calling thread where the platform has
 * other threads, and in place in the browser, where a dispatch would only delay the work.
 */
internal expect val enginePixelsContext: CoroutineContext
