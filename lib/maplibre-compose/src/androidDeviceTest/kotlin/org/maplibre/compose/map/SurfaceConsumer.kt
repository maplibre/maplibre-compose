package org.maplibre.compose.map

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeout

/**
 * A Surface that records the center pixel of every frame drawn into it. Consuming every image keeps
 * BufferQueue backpressure from stalling a presenter's synchronous teardown.
 */
internal class SurfaceConsumer(width: Int, height: Int) : AutoCloseable {
  private val thread = HandlerThread("map-test-surface-consumer").apply { start() }
  private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
  private val failure = MutableStateFlow<Throwable?>(null)
  private val latest = MutableStateFlow(Frame(count = 0L, centerArgb = 0))
  val surface: Surface = reader.surface

  /** Frames consumed so far. */
  val frames: Long
    get() = latest.value.count

  /** The center pixel of the latest frame, as an `android.graphics.Color` ARGB value. */
  val centerArgb: Int
    get() = latest.value.centerArgb

  /** Emits after each consumed frame and on a consumer failure. */
  val updates: Flow<*>
    get() = merge(latest, failure)

  init {
    reader.setOnImageAvailableListener(
      { source ->
        try {
          source.acquireLatestImage()?.use { image ->
            val plane = image.planes.single()
            val offset =
              (image.height / 2) * plane.rowStride + (image.width / 2) * plane.pixelStride
            val bytes = plane.buffer
            val argb =
              android.graphics.Color.argb(
                bytes.get(offset + 3).toInt() and 0xff,
                bytes.get(offset).toInt() and 0xff,
                bytes.get(offset + 1).toInt() and 0xff,
                bytes.get(offset + 2).toInt() and 0xff,
              )
            latest.value = Frame(latest.value.count + 1, argb)
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
      withTimeout(TimeoutMillis) {
        combine(latest, failure) { frame, error ->
            error?.let { throw AssertionError("Could not consume Surface pixels", it) }
            frame.count > after && frame.centerArgb == expectedArgb
          }
          .first { it }
      }
    } catch (error: Throwable) {
      throw AssertionError(
        "Expected Surface ARGB 0x${expectedArgb.toUInt().toString(16)} after frame $after; " +
          "latest frame $frames was 0x${centerArgb.toUInt().toString(16)}",
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
    thread.join(TimeoutMillis)
    assertFalse(thread.isAlive, "Image consumer thread did not stop")
    reader.close()
    surface.release()
    failure.value?.let { throw AssertionError("Could not consume Surface pixels", it) }
  }

  private class Frame(val count: Long, val centerArgb: Int)

  private companion object {
    const val TimeoutMillis = 10_000L
  }
}
