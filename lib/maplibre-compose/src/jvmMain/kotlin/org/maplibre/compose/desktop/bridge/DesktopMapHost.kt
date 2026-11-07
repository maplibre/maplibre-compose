package org.maplibre.compose.desktop.bridge

import org.maplibre.compose.desktop.ComposeGpuContext
import org.maplibre.compose.desktop.DesktopComposeMapPresentationHost
import org.maplibre.compose.mlnffi.MapRenderBackend
import org.maplibre.compose.mlnffi.MlnFfiFrameDemands
import org.maplibre.compose.mlnffi.MlnFfiMapFrame
import org.maplibre.compose.mlnffi.MlnFfiMapHost
import org.maplibre.compose.mlnffi.RenderBackendPair

/**
 * What every desktop host shares: MapLibre's session runs its lifecycle on [rendererThread], and
 * Compose draws its frames through a context of type [C].
 *
 * Compose's frame clock paces the map: the session's redraw wake invalidates the map, and the next
 * Compose frame demands a frame. The FFI offers no frame pacer for desktop windows.
 *
 * [close] tears everything down in order: held frames first, then the textures, then the producer
 * contexts on the renderer thread, then the thread.
 */
internal abstract class DesktopMapHost<C : ComposeGpuContext>(
  protected val presentationHost: DesktopComposeMapPresentationHost<C>,
  final override val backends: RenderBackendPair,
  rendererThreadName: String,
) : MlnFfiMapHost {
  protected val producer: MapRenderBackend
    get() = backends.producer

  final override val frameDemands: MlnFfiFrameDemands
    get() = MlnFfiFrameDemands.OnRedraw

  protected val rendererThread = MapRendererThread(rendererThreadName)

  // TODO: release frames with a GPU sync point once Skiko exposes a way to signal one after
  // Compose's reads, or to wait on the producer's. Until then each release waits on the CPU for
  // Compose's submitted work.
  protected val frameCompletion = ComposeFrameCompletion()

  /**
   * Runs [action] with exclusive access to Compose's context, or returns null while Compose has
   * none. With OpenGL, the context is current.
   */
  protected fun <R> withComposeContext(action: (C) -> R): R? = presentationHost.withContext(action)

  /** Drops what belonged to Compose's previous context, after Compose replaced it. */
  protected abstract fun contextReplaced()

  /** Releases every texture and the Skia wrappers around them, for [close]. */
  protected abstract fun closeTextures()

  /** Closes the producer's contexts after their textures are gone. Runs on the renderer thread. */
  protected abstract fun closeProducers()

  /** Wraps each action MapLibre runs on the renderer thread. */
  protected open fun <R> onRendererThread(action: () -> R): R = action()

  /**
   * [withComposeContext], after releasing the frames Compose has finished reading. With
   * [keepNewest], the newest frame stays held; see [ComposeFrameCompletion.prepare].
   */
  protected fun <R> withPreparedContext(keepNewest: Boolean = false, action: (C) -> R): R? =
    withComposeContext { context ->
      frameCompletion.prepare(context.skiaContext, keepNewest, ::contextReplaced)
      action(context)
    }

  override fun <R> withProducerAccess(frame: MlnFfiMapFrame, action: () -> R): R =
    withRendererAccess(action)

  override fun <R> withRendererAccess(action: () -> R): R = rendererThread.run {
    onRendererThread(action)
  }

  override fun enqueueRenderer(action: () -> Unit): Boolean = rendererThread.post {
    onRendererThread(action)
  }

  override fun completeProducerAccess(frame: MlnFfiMapFrame) {
    presentationHost.onGpuThread { holdFrame(frame) }
  }

  /** Takes over [frame]'s native ownership until Compose has read it. Runs with GPU access. */
  protected open fun holdFrame(frame: MlnFfiMapFrame) {
    frame.acquiredFrame = null
    frame.releaseAfterReads?.let(frameCompletion::holdFrame)
    frame.releaseAfterReads = null
  }

  final override fun completeConsumerAccess() {
    var completed = false
    try {
      completed = withPreparedContext {} != null
    } finally {
      if (!completed) presentationHost.onGpuThread { frameCompletion.abandon() }
    }
  }

  final override fun close() {
    try {
      try {
        completeConsumerAccess()
      } finally {
        try {
          presentationHost.onGpuThread { frameCompletion.abandon() }
        } finally {
          closeTextures()
        }
      }
    } finally {
      try {
        rendererThread.run(::closeProducers)
      } finally {
        rendererThread.close()
      }
    }
  }
}
