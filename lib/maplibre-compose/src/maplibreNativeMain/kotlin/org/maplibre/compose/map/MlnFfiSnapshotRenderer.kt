package org.maplibre.compose.map

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.compose.mlnffi.MlnFfiGate
import org.maplibre.compose.mlnffi.MlnFfiSnapshotTarget
import org.maplibre.compose.style.styleHostDispatcher
import org.maplibre.compose.util.toImageBitmap
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.NativeBuffer
import org.maplibre.nativeffi.render.RenderResult
import org.maplibre.nativeffi.render.RenderSessionHandle
import org.maplibre.nativeffi.render.RenderTargetExtent

/** How long the pump parks between render-update polls. */
private const val PUMP_POLL_MILLIS = 8L

/** How long a closing map waits for the snapshot thread to release its render session. */
private const val CLOSE_HANDSHAKE_TIMEOUT_MILLIS = 10_000L

/**
 * The still-image session's stand-in for a composed session's render access. Live render-session
 * hops answer as they do before a composed session's first frame; feature state reaches the image
 * through the pump's replay instead.
 */
private class SnapshotRenderAccess : MlnFfiRenderSessionAccess {
  @Volatile var renderRequested = true
  @Volatile var cancelRequested = false
  private val released = MlnFfiGate()

  override fun requestRender() {
    renderRequested = true
  }

  override fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T? = null

  override fun enqueueRenderSessionWork(work: (RenderSessionHandle?) -> Unit): Boolean = false

  /** Blocks until the pump releases the session, because only its thread may close it. */
  override fun closeRenderSession() {
    cancelRequested = true
    released.await(CLOSE_HANDSHAKE_TIMEOUT_MILLIS)
  }

  fun markReleased() {
    released.open()
  }
}

/**
 * Renders one still image of [core]'s loaded map into a session-owned texture and reads it back.
 *
 * The pinned FFI has no synchronous render-to-completion, so the pump polls [renderUpdate] on a
 * dedicated thread until the map reports itself fully loaded and one further frame has drawn that
 * state. [width] and [height] are the map's logical viewport size; the returned image is in
 * physical pixels at the core's scale factor.
 */
internal suspend fun renderStillImage(
  core: MlnFfiMapCore,
  target: MlnFfiSnapshotTarget,
  width: Int,
  height: Int,
  deadline: TimeSource.Monotonic.ValueTimeMark,
  loadFailure: () -> String?,
): ImageBitmap {
  // A fresh single-threaded dispatcher, because every render-session call must come from the
  // thread that attached it.
  val snapshotDispatcher = styleHostDispatcher()
  try {
    return withContext(snapshotDispatcher.dispatcher) {
      pumpStillImage(core, target, width, height, deadline, loadFailure)
    }
  } finally {
    snapshotDispatcher.close()
  }
}

private suspend fun pumpStillImage(
  core: MlnFfiMapCore,
  target: MlnFfiSnapshotTarget,
  width: Int,
  height: Int,
  deadline: TimeSource.Monotonic.ValueTimeMark,
  loadFailure: () -> String?,
): ImageBitmap {
  val access = SnapshotRenderAccess()
  core.attachRenderSession(access)
  try {
    val map = awaitMap(core, access, deadline, loadFailure)
    val scaleFactor = core.runtimeLoop?.scaleFactor ?: 1.0
    val extent =
      RenderTargetExtent(
        width = width.coerceAtLeast(1),
        height = height.coerceAtLeast(1),
        scaleFactor = scaleFactor,
      )
    val session = target.attach(map, extent)
    try {
      core.markFeatureStateReplayPending()
      // The still-image target supplies the map's real dimensions, as a composed target would.
      core.publishAttachedViewport()
      var sealed = false
      while (true) {
        checkSnapshotHealthy(core, access, deadline, loadFailure)
        access.renderRequested = false
        val update = session.renderUpdate()
        if (update.result == RenderResult.RENDERED) {
          core.replayPendingFeatureState(session)
          val settled = !update.needsRepaint && !access.renderRequested
          if (sealed && settled) break
          if (!sealed && settled && core.isMapFullyLoaded()) {
            sealed = true
            // One more frame after the load report, so the image holds the fully loaded state.
            core.postSnapshotRepaint()
          }
        }
        delay(PUMP_POLL_MILLIS)
      }
      val info = session.textureImageInfo()
      return NativeBuffer.allocate(info.byteLength).use { buffer ->
        val read = session.readPremultipliedRgba8(buffer)
        premultipliedRgba8ToImageBitmap(buffer.toByteArray(), read.width, read.height, read.stride)
      }
    } finally {
      runCatching { session.close() }
    }
  } finally {
    // The gate opens on every exit path, or a close during the map wait blocks out the handshake.
    access.markReleased()
    core.detachRenderSession(access)
  }
}

/** Waits for the runtime loop to create the map. */
private suspend fun awaitMap(
  core: MlnFfiMapCore,
  access: SnapshotRenderAccess,
  deadline: TimeSource.Monotonic.ValueTimeMark,
  loadFailure: () -> String?,
): MapHandle {
  while (true) {
    checkSnapshotHealthy(core, access, deadline, loadFailure)
    core.runtimeLoop?.map?.let {
      return it
    }
    delay(PUMP_POLL_MILLIS)
  }
}

private fun checkSnapshotHealthy(
  core: MlnFfiMapCore,
  access: SnapshotRenderAccess,
  deadline: TimeSource.Monotonic.ValueTimeMark,
  loadFailure: () -> String?,
) {
  check(!access.cancelRequested && !core.isClosed) {
    "MapState was closed while a snapshot was rendering"
  }
  core.runtimeLoop?.failure?.let {
    throw IllegalStateException("The MapLibre map runtime failed", it)
  }
  loadFailure()?.let { reason -> throw IllegalStateException("The map failed to load: $reason") }
  check(deadline.hasNotPassedNow()) { "The map did not finish rendering a snapshot in time" }
}

/** [stride] is in bytes; rows read back top-first. */
private fun premultipliedRgba8ToImageBitmap(
  bytes: ByteArray,
  width: Int,
  height: Int,
  stride: Int,
): ImageBitmap {
  val pixels = IntArray(width * height)
  for (y in 0 until height) {
    val row = y * stride
    for (x in 0 until width) {
      val i = row + x * 4
      val a = bytes[i + 3].toInt() and 0xff
      // toImageBitmap takes straight-alpha color ints, so the premultiplied channels divide out.
      val r = unpremultiply(bytes[i].toInt() and 0xff, a)
      val g = unpremultiply(bytes[i + 1].toInt() and 0xff, a)
      val b = unpremultiply(bytes[i + 2].toInt() and 0xff, a)
      pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
  }
  return pixels.toImageBitmap(width, height)
}

private fun unpremultiply(channel: Int, alpha: Int): Int =
  if (alpha == 0 || alpha == 255) channel
  else ((channel * 255 + alpha / 2) / alpha).coerceAtMost(255)
