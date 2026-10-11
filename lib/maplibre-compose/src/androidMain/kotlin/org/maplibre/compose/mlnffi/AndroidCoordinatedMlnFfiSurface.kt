package org.maplibre.compose.mlnffi

import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.hardware.HardwareBuffer
import android.hardware.SyncFence
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.ComposeMapSurface
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.mapSurface

/** SurfaceView supplies the window hole and geometry; its child holds the selected map buffer. */
@RequiresApi(33)
@Composable
internal fun AndroidCoordinatedMlnFfiSurface(
  renderer: MlnFfiMapRenderer,
  backend: MapRenderBackend,
  maximumFps: Int?,
  modifier: Modifier,
  logger: MapLog?,
  presentWindow: Boolean,
) {
  val surface =
    remember(renderer, backend) { AndroidCoordinatedMapSurface(renderer, backend, logger) }
  val density by rememberUpdatedState(LocalDensity.current.density.toDouble())
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  SideEffect { surface.controller.setMaximumFps(maximumFps) }
  DisposableEffect(surface, lifecycle) {
    val observer = LifecycleEventObserver { _, _ ->
      surface.controller.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    lifecycle.addObserver(observer)
    surface.controller.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    onDispose { lifecycle.removeObserver(observer) }
  }
  DisposableEffect(surface) { onDispose { surface.close() } }
  if (!presentWindow) {
    androidx.compose.foundation.layout.Box(modifier)
    return
  }
  key(surface) {
    AndroidView(
      modifier = modifier.graphicsLayer().mapSurface(surface, presentFrames = true),
      factory = { context ->
        SurfaceView(context).apply {
          holder.setFormat(PixelFormat.TRANSLUCENT)
          holder.addCallback(
            object : SurfaceHolder.Callback2 {
              override fun surfaceCreated(holder: SurfaceHolder) = Unit

              override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
              ) {
                surface.bind(this@apply, width, height, density)
              }

              override fun surfaceDestroyed(holder: SurfaceHolder) {
                surface.unbind()
              }

              override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
                // A transparent parent buffer lets SurfaceView finish its redraw and punch the
                // hole.
                // Map buffers belong to its child, so this never overwrites a selected map image.
                if (surface.coordinated) {
                  val canvas = holder.surface.lockCanvas(null)
                  try {
                    canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                  } finally {
                    holder.surface.unlockCanvasAndPost(canvas)
                  }
                }
              }
            }
          )
        }
      },
      update = { surface.updateDensity(density) },
    )
  }
}

/**
 * Selects completed images on the UI thread before overlays are placed. The renderer never waits
 * for a window draw: a bounded pool pauses production until an image is returned by its consumer.
 */
@RequiresApi(33)
internal class AndroidCoordinatedMapSurface(
  private val renderer: MlnFfiMapRenderer,
  backend: MapRenderBackend,
  private val logger: MapLog?,
) : ComposeMapSurface {
  private val mainHandler = Handler(Looper.getMainLooper())
  // Only the render thread visits the pool's projection queue; UI writes occur between detaches.
  @Volatile private var pool: AndroidMapImagePool? = null
  private var view: SurfaceView? = null
  private var child: SurfaceControl? = null
  private var extent = MapExtent.Empty
  private var pending: AndroidMapImageFrame? = null
  private var selected: AndroidMapImageFrame? = null
  private var destination: MlnFfiMapDestination? = null
  private var requestFrame: () -> Unit = {}
  private var enabled = true
  private var closed = false

  val coordinated: Boolean
    get() = child != null

  private val capturingRenderer =
    object : MlnFfiMapRenderer by renderer {
      override fun render(
        host: MlnFfiMapHostSession,
        frame: MlnFfiMapFrame,
        captureProjection: Boolean,
      ): MlnFfiFrameResult {
        val pool = pool ?: return renderer.render(host, frame)
        if (!pool.acquire()) return MlnFfiFrameResult.AwaitUpdate
        var completed = false
        try {
          val result = renderer.render(host, frame, captureProjection = true)
          if (result is MlnFfiFrameResult.Rendered) {
            completed = true
            pool.rendered(checkNotNull(result.projection))
          }
          return result
        } finally {
          if (!completed) pool.releaseSlot(wakeProducer = false)
        }
      }
    }

  val controller =
    AndroidMlnFfiSurfaceController(capturingRenderer, backend, logger) {
      mainHandler.post {
        logger?.e(it) { "The coordinated Android map surface failed" }
        close()
      }
    }

  // The render thread already applies the map FPS cap; ready images must reach placement promptly.
  override val maximumFps: Int?
    get() = null

  override fun attach(requestFrame: () -> Unit) {
    this.requestFrame = requestFrame
    if (pending != null) requestFrame()
  }

  override fun detach() {
    requestFrame = {}
  }

  override fun setPresentFrames(value: Boolean) {
    enabled = value
    if (!value) clearPresentation()
  }

  fun updateDensity(density: Double) {
    val view = view ?: return
    if (extent.scaleFactor != density) bind(view, view.width, view.height, density)
  }

  fun bind(view: SurfaceView, width: Int, height: Int, density: Double) {
    if (closed || width <= 0 || height <= 0) return
    val next = MapExtent.fromPhysical(width, height, density)
    if (this.view === view && next == extent) return
    retireProducer()
    this.view = view
    extent = next
    val root = view.rootSurfaceControl
    if (root == null || !view.isHardwareAccelerated) {
      useLegacySurface(view, width, height, density)
      return
    }
    val nextPool =
      try {
        AndroidMapImagePool(next, controller::requestFrame) { frame ->
          mainHandler.post {
            if (closed || pool !== frame.pool || !enabled) frame.discard()
            else {
              pending?.discard()
              pending = frame
              requestFrame()
            }
          }
        }
      } catch (error: RuntimeException) {
        if (error !is IllegalArgumentException && error !is UnsupportedOperationException)
          throw error
        logger?.w(error) {
          "Android cannot allocate coordinated map buffers; using the SurfaceView"
        }
        useLegacySurface(view, width, height, density)
        return
      }
    if (child == null) {
      child =
        SurfaceControl.Builder()
          .setName("MapLibre Compose map")
          .setParent(view.surfaceControl)
          .setFormat(PixelFormat.RGBA_8888)
          .build()
    }
    pool = nextPool
    controller.observeImages(nextPool.reader) { nextPool.drain() }
    controller.surfaceCreated(nextPool.reader.surface, width, height, density)
    requestFrame()
  }

  private fun useLegacySurface(view: SurfaceView, width: Int, height: Int, density: Double) {
    clearPresentation()
    releaseChild()
    controller.surfaceCreated(view.holder.surface, width, height, density)
  }

  /**
   * Stops native access before retiring a reader; submitted images outlive their reader binding.
   */
  private fun retireProducer() {
    controller.surfaceDestroyed()
    val previous = pool
    pool = null
    if (previous != null) controller.withRendererAccess { previous.retire() }
    pending?.discard()
    pending = null
  }

  fun unbind() {
    if (closed) return
    retireProducer()
    clearPresentation()
    releaseChild()
    view = null
    extent = MapExtent.Empty
  }

  private fun releaseChild() {
    child?.let {
      SurfaceControl.Transaction().use { transaction -> transaction.reparent(it, null).apply() }
      it.release()
    }
    child = null
  }

  override fun prepare(extent: MapExtent): Boolean = pending != null

  override fun present(extent: MapExtent) {
    if (closed || !enabled || extent.isEmpty) {
      clearPresentation()
      return
    }
    val child = child ?: return
    val view = view ?: return
    val root = view.rootSurfaceControl ?: return
    val candidate = pending
    val frame = candidate ?: selected ?: return
    // Preserve physical pixels and the camera anchor while a replacement extent is rendering.
    val destination =
      MlnFfiMapDestination(
        (extent.physicalWidth - frame.projection.extent.physicalWidth) / 2,
        (extent.physicalHeight - frame.projection.extent.physicalHeight) / 2,
        frame.projection.extent.physicalWidth,
        frame.projection.extent.physicalHeight,
      )
    if (candidate == null && this.destination == destination) {
      renderer.presentFrame(frame.projection, destination, extent.scaleFactor)
      return
    }
    val transaction = SurfaceControl.Transaction()
    // Android 13 retains this Java transaction until its render-thread draw callback runs.
    // Closing it at applyTransactionOnDraw's return would remove the pending map buffer.
    // A weak reference also lets an abandoned window collect an unapplied transaction.
    val transactionReference = WeakReference(transaction)
    transaction.addTransactionCommittedListener({ mainHandler.post(it) }) {
      transactionReference.get()?.close()
    }
    val accepted =
      try {
        transaction
          .setLayer(child, 1)
          .setVisibility(child, true)
          .setPosition(child, destination.left.toFloat(), destination.top.toFloat())
        if (candidate != null) candidate.setBuffer(transaction, child)
        if (root.applyTransactionOnDraw(transaction)) true
        else {
          // Keep ownership with the compositor even when the window cannot coordinate a draw.
          // The fallback below clears this hidden buffer and receives its consumer release fence.
          transaction.setVisibility(child, false).apply()
          false
        }
      } catch (error: Throwable) {
        transaction.close()
        throw error
      }
    if (!accepted) {
      retireProducer()
      useLegacySurface(
        view,
        this.extent.physicalWidth,
        this.extent.physicalHeight,
        this.extent.scaleFactor,
      )
      return
    }
    if (candidate != null) {
      pending = null
      val previous = selected
      selected = candidate
      renderer.presentFrame(candidate.projection, destination, extent.scaleFactor)
      previous?.projection?.close()
    } else renderer.presentFrame(frame.projection, destination, extent.scaleFactor)
    this.destination = destination
    // applyTransactionOnDraw doesn't schedule a window draw on its own.
    view.invalidate()
  }

  override fun draw(scope: DrawScope) {
    (scope as ContentDrawScope).drawContent()
  }

  private fun clearPresentation() {
    renderer.presentFrame(null, MlnFfiMapDestination(0, 0, 0, 0), 1.0)
    pending?.discard()
    pending = null
    selected?.projection?.close()
    selected = null
    destination = null
    child?.let { control ->
      SurfaceControl.Transaction().use {
        it.setBuffer(control, null).setVisibility(control, false).apply()
      }
    }
  }

  override fun close() {
    if (closed) return
    unbind()
    closed = true
    requestFrame = {}
    controller.close()
  }
}

/** One generation of buffers. All pending projections belong to the render thread. */
@RequiresApi(33)
internal class AndroidMapImagePool(
  extent: MapExtent,
  private val requestRender: () -> Unit,
  private val onFrame: (AndroidMapImageFrame) -> Unit,
) {
  init {
    if (
      !HardwareBuffer.isSupported(
        extent.physicalWidth,
        extent.physicalHeight,
        HardwareBuffer.RGBA_8888,
        1,
        BufferUsage,
      )
    ) {
      throw UnsupportedOperationException(
        "The Android allocator does not support coordinated map buffers"
      )
    }
  }

  val reader: ImageReader =
    ImageReader.newInstance(
      extent.physicalWidth,
      extent.physicalHeight,
      PixelFormat.RGBA_8888,
      MaxImages,
      BufferUsage,
    )
  private val outstanding = AtomicInteger()
  private val projections = ArrayDeque<MlnFfiMapFrameProjection>()
  @Volatile private var retired = false
  private val readerClosed = AtomicBoolean()

  fun acquire(): Boolean {
    if (retired) return false
    while (true) {
      val count = outstanding.get()
      // Leave a producer slot free, so eglSwapBuffers cannot block teardown behind the UI thread.
      if (count >= MaxImages - 1) return false
      if (outstanding.compareAndSet(count, count + 1)) return true
    }
  }

  fun rendered(projection: MlnFfiMapFrameProjection) {
    projections.addLast(projection)
    drain()
  }

  fun drain() {
    if (retired) return
    while (projections.isNotEmpty()) {
      // Never acquireLatestImage: dropping a buffer would break the projection's frame order.
      val image = reader.acquireNextImage() ?: return
      val projection = projections.removeFirst()
      val frame =
        try {
          AndroidMapImageFrame(this, image, projection)
        } catch (error: Throwable) {
          try {
            projection.close()
          } finally {
            image.close()
            releaseSlot()
          }
          throw error
        }
      onFrame(frame)
    }
  }

  fun releaseSlot(wakeProducer: Boolean = true) {
    val remaining = outstanding.decrementAndGet()
    if (retired && remaining == 0) closeReader() else if (!retired && wakeProducer) requestRender()
  }

  fun retire() {
    reader.setOnImageAvailableListener(null, null)
    while (projections.isNotEmpty()) {
      try {
        projections.removeFirst().close()
      } finally {
        releaseSlot(wakeProducer = false)
      }
    }
    retired = true
    if (outstanding.get() == 0) closeReader()
  }

  private fun closeReader() {
    if (readerClosed.compareAndSet(false, true)) reader.close()
  }

  private companion object {
    const val MaxImages = 4
    val BufferUsage =
      HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
        HardwareBuffer.USAGE_COMPOSER_OVERLAY
  }
}

/** Image ownership passes to SurfaceFlinger; projection ownership stays with the UI selection. */
@RequiresApi(33)
internal class AndroidMapImageFrame(
  val pool: AndroidMapImagePool,
  private val image: Image,
  val projection: MlnFfiMapFrameProjection,
) {
  private val released = AtomicBoolean()
  private val buffer = checkNotNull(image.hardwareBuffer)
  private var submitted = false

  fun setBuffer(transaction: SurfaceControl.Transaction, child: SurfaceControl) {
    image.fence.use { producerFence ->
      transaction.setBuffer(child, buffer, producerFence) { consumerFence ->
        consumerFence.use(::releaseImage)
      }
    }
    submitted = true
  }

  fun discard() {
    try {
      projection.close()
    } finally {
      if (!submitted) image.fence.use(::releaseImage)
    }
  }

  private fun releaseImage(fence: SyncFence) {
    if (!released.compareAndSet(false, true)) return
    try {
      try {
        image.fence = fence
      } catch (error: java.io.IOException) {
        fence.awaitForever()
      }
    } finally {
      try {
        image.close()
      } finally {
        try {
          buffer.close()
        } finally {
          pool.releaseSlot()
        }
      }
    }
  }
}
