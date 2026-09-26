package org.maplibre.compose.map

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFalse
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * A Surface that records the center pixel of every frame drawn into it. Consuming every image keeps
 * BufferQueue backpressure from stalling a presenter's synchronous teardown.
 */
internal class SurfaceConsumer(width: Int, height: Int) : AutoCloseable {
  private val thread = HandlerThread("map-test-surface-consumer").apply { start() }
  private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
  private val failure = AtomicReference<Throwable?>(null)
  val surface: Surface = reader.surface

  /** Frames consumed so far. Incremented after [centerArgb] holds the frame's pixel. */
  val frames = AtomicLong()

  /** The center pixel of the latest frame, as an `android.graphics.Color` ARGB value. */
  val centerArgb = AtomicInteger()

  init {
    reader.setOnImageAvailableListener(
      { source ->
        try {
          source.acquireLatestImage()?.use { image ->
            val plane = image.planes.single()
            val offset =
              (image.height / 2) * plane.rowStride + (image.width / 2) * plane.pixelStride
            val bytes = plane.buffer
            centerArgb.set(
              android.graphics.Color.argb(
                bytes.get(offset + 3).toInt() and 0xff,
                bytes.get(offset).toInt() and 0xff,
                bytes.get(offset + 1).toInt() and 0xff,
                bytes.get(offset + 2).toInt() and 0xff,
              )
            )
            frames.incrementAndGet()
          }
        } catch (error: Throwable) {
          failure.compareAndSet(null, error)
        }
      },
      Handler(thread.looper),
    )
  }

  /** Waits for a frame later than [after] whose center pixel is [expectedArgb]. */
  suspend fun awaitColor(expectedArgb: Int, after: Long = 0L) {
    try {
      withTimeout(TIMEOUT_MILLIS) {
        while (true) {
          failure.get()?.let { throw AssertionError("Could not consume Surface pixels", it) }
          if (frames.get() > after && centerArgb.get() == expectedArgb) return@withTimeout
          delay(10)
        }
      }
    } catch (error: Throwable) {
      throw AssertionError(
        "Expected Surface ARGB 0x${expectedArgb.toUInt().toString(16)} after frame $after; " +
          "latest frame ${frames.get()} was 0x${centerArgb.get().toUInt().toString(16)}",
        error,
      )
    }
  }

  /**
   * Stops the consumer thread before closing the reader. ImageReader.close invalidates every
   * acquired Image, so a callback still reading one on the consumer thread would throw and, being
   * uncaught there, crash the test process.
   */
  override fun close() {
    reader.setOnImageAvailableListener(null, null)
    thread.quitSafely()
    thread.join(TIMEOUT_MILLIS)
    assertFalse(thread.isAlive, "Image consumer thread did not stop")
    reader.close()
    surface.release()
    failure.get()?.let { throw AssertionError("Could not consume Surface pixels", it) }
  }

  private companion object {
    const val TIMEOUT_MILLIS = 10_000L
  }
}
