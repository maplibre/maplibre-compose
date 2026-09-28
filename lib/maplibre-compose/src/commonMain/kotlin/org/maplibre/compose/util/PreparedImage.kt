package org.maplibre.compose.util

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import org.maplibre.compose.map.ResolvedStyleImage
import org.maplibre.compose.sources.ImageSource
import org.maplibre.compose.sources.MutableImageSourceHandle

/**
 * Immutable pixels that a map uploads without further conversion.
 *
 * [fromBitmap] does all per-pixel work once. Submitting a prepared image, through
 * [MutableImageSourceHandle.setImage], [ImageSource], or [ResolvedStyleImage], only passes a
 * reference. A prepared image belongs to no map or loaded style: reuse it across maps, styles,
 * handles, snapshotters, and repeated submissions, such as the frames of a looping animation.
 *
 * Two prepared images are equal when their sizes and pixels match. Comparing an image with itself,
 * or with an image whose hash differs, does not read pixels.
 */
@Immutable
public class PreparedImage internal constructor(internal val pixels: EnginePixels) {
  private val hash = pixels.hashCode()

  public val width: Int
    get() = pixels.width

  public val height: Int
    get() = pixels.height

  /**
   * Returns a new bitmap on each call; changing it does not change this image. On MapLibre Native,
   * a fully transparent pixel reads back as transparent black.
   */
  public fun toImageBitmap(): ImageBitmap = pixels.toStraightArgb().toImageBitmap(width, height)

  override fun equals(other: Any?): Boolean =
    this === other || other is PreparedImage && hash == other.hash && pixels == other.pixels

  override fun hashCode(): Int = hash

  override fun toString(): String = "PreparedImage(${width}x$height)"

  public companion object {
    /**
     * Copies [bitmap] and converts it to the form the map uploads. This runs on the calling thread
     * and takes time proportional to the pixel count, so prepare large or frequent images, such as
     * animation frames, off the main thread. In the browser, everything runs on one thread.
     *
     * Do not draw into [bitmap] while this runs. Later changes to [bitmap] have no effect on the
     * result.
     *
     * @throws IllegalArgumentException if [bitmap] has a zero width or height.
     */
    public fun fromBitmap(bitmap: ImageBitmap): PreparedImage =
      bitmap.readStraightArgb().let { PreparedImage(enginePixels(it.width, it.height, it.argb)) }
  }
}

/**
 * Prepares a bitmap that the library rendered. Its pixels are read on the calling thread, which
 * renders painters (the main thread on Android), and converted in [enginePixelsContext], so
 * rendering never pays for the conversion.
 */
internal suspend fun prepareRenderedImage(bitmap: ImageBitmap): PreparedImage {
  val read = bitmap.readStraightArgb()
  return withContext(enginePixelsContext) {
    PreparedImage(enginePixels(read.width, read.height, read.argb))
  }
}

private class StraightArgb(val width: Int, val height: Int, val argb: IntArray)

private fun ImageBitmap.readStraightArgb(): StraightArgb {
  require(width > 0 && height > 0) { "A prepared image needs pixels, but got ${width}x$height" }
  val argb = IntArray(width * height)
  readPixels(argb)
  return StraightArgb(width, height, argb)
}

/** Pixels in the form the platform's engine uploads. Equality compares size and content. */
internal expect class EnginePixels {
  val width: Int
  val height: Int

  /** Returns a new array of straight-alpha ARGB pixels, the format of [ImageBitmap.readPixels]. */
  fun toStraightArgb(): IntArray
}

/** Converts [straightArgb], which the result may take ownership of, to engine pixels. */
internal expect fun enginePixels(width: Int, height: Int, straightArgb: IntArray): EnginePixels

/**
 * Where the library converts pixels it rendered itself: off the rendering thread where the platform
 * has other threads, and in place in the browser, where a dispatch would only delay the work.
 */
internal expect val enginePixelsContext: CoroutineContext
