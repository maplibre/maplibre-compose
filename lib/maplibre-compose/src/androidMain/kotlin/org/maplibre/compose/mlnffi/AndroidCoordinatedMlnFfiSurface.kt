package org.maplibre.compose.mlnffi

import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
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
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.ComposeMapSurface
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.mapSurface

/**
 * A SurfaceView supplies the window hole and geometry; its child layer shows the map buffer that
 * Compose placed overlays for, in the same window draw.
 */
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
    remember(renderer, backend) {
      AndroidCoordinatedMapSurface(renderer, backend, maximumFps, logger)
    }
  val density by rememberUpdatedState(LocalDensity.current.density.toDouble())
  val lifecycleOwner = LocalLifecycleOwner.current

  SideEffect { surface.controller.setMaximumFps(maximumFps) }

  DisposableEffect(surface, lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> surface.controller.setActive(true)
        Lifecycle.Event.ON_STOP -> surface.controller.setActive(false)
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    surface.controller.setActive(
      lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
    )
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  DisposableEffect(surface) { onDispose { surface.close() } }

  // An empty SurfaceView would punch a black hole; styles load without a surface.
  if (!presentWindow) {
    Box(modifier)
    return
  }

  // Do not reuse a SurfaceView whose callback belongs to a disposed renderer session.
  // https://issuetracker.google.com/issues/554586999
  key(surface) {
    AndroidView(
      // Force a traversal for a replacement SurfaceView that would otherwise wait for another
      // window invalidation. https://issuetracker.google.com/issues/554732248
      modifier = modifier.graphicsLayer().mapSurface(surface, presentFrames = true),
      factory = { context ->
        SurfaceView(context).apply {
          // Never opaque: before its first frame the hole would otherwise read as black.
          holder.setFormat(PixelFormat.TRANSLUCENT)
          holder.addCallback(
            object : SurfaceHolder.Callback {
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
            },
          )
        }
      },
      update = { surface.updateDensity(density) },
    )
  }
}

/**
 * Presents map buffers in a child layer of a SurfaceView, in the window draw whose overlays Compose
 * placed with the buffer's projection. The UI thread selects completed frames before overlay
 * placement and never waits for rendering; the render thread never waits for a window draw.
 *
 * Falls back to presenting into the SurfaceView itself when the window cannot coordinate a draw or
 * the device cannot import map buffers.
 */
@RequiresApi(33)
internal class AndroidCoordinatedMapSurface(
  private val renderer: MlnFfiMapRenderer,
  backend: MapRenderBackend,
  maximumFps: Int?,
  private val logger: MapLog?,
) : ComposeMapSurface {
  private val mainHandler = Handler(Looper.getMainLooper())
  private var view: SurfaceView? = null
  private var child: SurfaceControl? = null
  private var extent = MapExtent.Empty
  private var pending: AndroidMapBufferFrame? = null
  private var selected: AndroidMapBufferFrame? = null
  private var destination: MlnFfiMapDestination? = null
  private var requestFrame: () -> Unit = {}
  private var enabled = true
  private var closed = false

  val controller =
    AndroidMlnFfiSurfaceController(
      renderer,
      backend,
      logger,
      maximumFps,
      onBufferFrame = { frame -> mainHandler.post { offer(frame) } },
    )

  // The render thread already applies the map's frame-rate limit; ready frames must reach
  // placement promptly.
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
    val resized = this.view === view
    this.view = view
    extent = next
    if (resized) {
      controller.surfaceChanged(width, height, density)
      return
    }
    if (
      view.rootSurfaceControl == null ||
        !view.isHardwareAccelerated ||
        !AndroidMapBuffers.isSupported(next)
    ) {
      logger?.i { "Android cannot coordinate map buffers with the window; using the SurfaceView" }
      useSurfaceView(view)
      return
    }
    child =
      SurfaceControl.Builder()
        .setName("MapLibre Compose map")
        .setParent(view.surfaceControl)
        .setFormat(PixelFormat.RGBA_8888)
        .build()
    controller.buffersCreated(width, height, density) { error ->
      mainHandler.post {
        if (this.view !== view || child == null) return@post
        logger?.w(error) { "Android cannot import coordinated map buffers; using the SurfaceView" }
        useSurfaceView(view)
      }
    }
  }

  private fun useSurfaceView(view: SurfaceView) {
    clearPresentation()
    releaseChild()
    controller.surfaceCreated(
      view.holder.surface,
      extent.physicalWidth,
      extent.physicalHeight,
      extent.scaleFactor,
    )
  }

  fun unbind() {
    if (closed) return
    controller.surfaceDestroyed()
    clearPresentation()
    releaseChild()
    view = null
    extent = MapExtent.Empty
  }

  private fun offer(frame: AndroidMapBufferFrame) {
    if (closed || !enabled || child == null) {
      frame.discard()
      return
    }
    pending?.discard()
    pending = frame
    requestFrame()
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
    val frameExtent = frame.projection.extent
    // Keep physical pixels and the camera anchor while a resized frame is rendering.
    val destination =
      MlnFfiMapDestination(
        (extent.physicalWidth - frameExtent.physicalWidth) / 2,
        (extent.physicalHeight - frameExtent.physicalHeight) / 2,
        frameExtent.physicalWidth,
        frameExtent.physicalHeight,
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
        candidate?.setBuffer(transaction, child)
        if (root.applyTransactionOnDraw(transaction)) true
        else {
          // Hand the buffer to SurfaceFlinger even though the window cannot coordinate a draw;
          // clearing it below returns the buffer's slot.
          transaction.setVisibility(child, false).apply()
          false
        }
      } catch (error: Throwable) {
        transaction.close()
        throw error
      }
    if (candidate != null) {
      pending = null
      val previous = selected
      selected = candidate
      previous?.projection?.close()
    }
    if (!accepted) {
      useSurfaceView(view)
      return
    }
    renderer.presentFrame(frame.projection, destination, extent.scaleFactor)
    // Placement observes the presentation revision. Notify those reads before this window draw;
    // Android's global snapshot observer otherwise schedules them after the current traversal.
    Snapshot.sendApplyNotifications()
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
