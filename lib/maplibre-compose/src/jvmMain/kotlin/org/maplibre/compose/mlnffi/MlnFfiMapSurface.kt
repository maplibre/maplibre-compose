package org.maplibre.compose.mlnffi

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.ComposeMapSurface
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.mapSurface
import org.maplibre.compose.util.rethrowIfFatal

/**
 * The node schedules preparation before overlay placement; the controller owns the presentation.
 */
@Composable
internal fun MlnFfiMapSurface(
  renderer: MlnFfiMapRenderer,
  hostResult: MlnFfiMapHostResult,
  modifier: Modifier = Modifier,
  logger: MapLog? = null,
  presentFrames: Boolean = true,
) {
  val controller =
    remember(renderer, hostResult) { MlnFfiSurfaceController(renderer, hostResult, logger) }
  // Node detachment can be temporary; composition owns the platform resources.
  DisposableEffect(controller) { onDispose { controller.close() } }
  Box(modifier.mapSurface(controller, presentFrames))
}

@OptIn(ExperimentalAtomicApi::class)
internal class MlnFfiSurfaceController(
  private val renderer: MlnFfiMapRenderer,
  private val hostResult: MlnFfiMapHostResult,
  private val logger: MapLog?,
) : ComposeMapSurface {
  private val host = (hostResult as? MlnFfiMapHostResult.Created)?.host
  private var session: MlnFfiMapHostSession? = null
  @Volatile private var requestFrame: () -> Unit = {}
  private var enabled = true
  private val failed = AtomicBoolean(false)
  @Volatile private var closed = false
  private var nextFrameId = 1L
  private var failures = 0
  private var configuredExtent = MapExtent.Empty
  private var presentation: CompletedPresentation? = null
  private var destination: MlnFfiMapDestination? = null
  private var presentationExtent = MapExtent.Empty
  private var destinationAnchor: MlnFfiMapPresentationAnchor? = null

  override val maximumFps: Int?
    get() = renderer.maximumFps

  override fun setPresentFrames(value: Boolean) {
    if (enabled == value) return
    enabled = value
    requestFrame()
  }

  override fun attach(requestFrame: () -> Unit) {
    this.requestFrame = requestFrame
    if (session != null) {
      requestFrame()
      return
    }
    when (hostResult) {
      is MlnFfiMapHostResult.Failed -> {
        fail(hostResult.cause ?: IllegalStateException(hostResult.diagnostic))
      }
      is MlnFfiMapHostResult.Created -> {
        val session = MlnFfiMapHostSessionImpl(hostResult.host, { closed }) { this.requestFrame() }
        this.session = session
        offerSurface(session)
      }
    }
  }

  private fun offerSurface(session: MlnFfiMapHostSession) {
    try {
      check(
        session.enqueueRenderer {
          if (!closed) {
            try {
              renderer.onSurfaceAvailable(session)
              this.requestFrame()
            } catch (error: Throwable) {
              fail(error)
            }
          }
        }
      ) {
        "The map host closed before accepting its surface"
      }
    } catch (error: Throwable) {
      fail(error)
    }
  }

  override fun detach() {
    requestFrame = {}
  }

  override fun prepare(extent: MapExtent): Boolean {
    val host = host ?: return false
    if (closed || failed.load() || !enabled || extent.isEmpty) return false
    val frameId = nextFrameId++
    try {
      if (configuredExtent != extent) {
        host.resize(extent)
        renderer.onSurfaceChanged(extent)
        configuredExtent = extent
      }
      val acquired = host.acquireFrame(extent)
      if (acquired == MlnFfiMapFrameAcquisition.NotReady) {
        requestFrame()
        return false
      }
      val frame = (acquired as MlnFfiMapFrameAcquisition.Acquired).frame
      var candidate: CompletedPresentation? = null
      try {
        host.withProducerAccess(frame) {
          when (
            val result = renderer.render(checkNotNull(session), frame, captureProjection = true)
          ) {
            is MlnFfiFrameResult.Rendered -> {
              // Own the handle before reading any properties or leaving producer access.
              candidate = CompletedPresentation(frame.target, result.projection)
              candidate!!.anchor = result.projection?.anchor ?: renderer.presentationAnchor(extent)
            }
            MlnFfiFrameResult.AwaitUpdate -> Unit
            MlnFfiFrameResult.RetryNextFrame -> requestFrame()
          }
          if (presentationExtent != extent || candidate != null) {
            destinationAnchor = candidate?.anchor ?: renderer.presentationAnchor(extent)
            presentationExtent = extent
          }
        }
        if (candidate == null) return false
        host.completeProducerAccess(frame)
        clearPresentation()
        presentation = candidate
        candidate = null
        return true
      } finally {
        try {
          candidate?.close()
        } finally {
          host.releaseFrame(frame)
        }
      }
    } catch (error: Throwable) {
      recover(error, frameId)
      return false
    }
  }

  override fun present(extent: MapExtent) {
    val completed = presentation
    if (closed || failed.load() || !enabled || extent.isEmpty || completed == null) {
      destination = null
      renderer.presentFrame(null, MlnFfiMapDestination(0, 0, 0, 0), 1.0)
      return
    }
    val anchor = if (presentationExtent == extent) destinationAnchor else null
    val destination =
      presentationDestination(
        completed.target.extent,
        completed.anchor,
        anchor
          ?: MlnFfiMapPresentationAnchor(
            extent.physicalWidth / 2 + completed.anchor.x -
              completed.target.extent.physicalWidth / 2,
            extent.physicalHeight / 2 + completed.anchor.y -
              completed.target.extent.physicalHeight / 2,
          ),
      )
    this.destination = destination
    renderer.presentFrame(completed.projection, destination, extent.scaleFactor)
  }

  override fun draw(scope: DrawScope) {
    val completed = presentation
    val destination = destination
    var drew = false
    if (!closed && !failed.load() && completed != null && destination != null) {
      try {
        drew = host?.draw(scope, completed.target, destination) == true
        if (drew && !completed.presented) {
          // Hosts throw a recoverable failure once per graphics-device change to rebuild the
          // session on the new device, so a rebuild that presents an image starts a fresh budget.
          completed.presented = true
          failures = 0
        }
        if (!drew) requestFrame()
      } catch (error: Throwable) {
        recover(error, nextFrameId - 1)
      }
    }
    if (!drew) scope.drawRect(Color.Transparent)
  }

  private fun clearPresentation() {
    renderer.presentFrame(null, MlnFfiMapDestination(0, 0, 0, 0), 1.0)
    val previous = presentation
    presentation = null
    destination = null
    previous?.close()
  }

  private fun recover(error: Throwable, frameId: Long) {
    rethrowIfFatal(error)
    clearPresentation()
    if (error !is MlnFfiRecoverableFrameException || ++failures > MaxRenderRecoveryAttempts) {
      fail(error)
      return
    }
    logger?.w(error) {
      "Map frame $frameId failed; rebuilding the render session (attempt $failures of $MaxRenderRecoveryAttempts)"
    }
    try {
      session?.let(renderer::onSurfaceLost)
      offerSurface(checkNotNull(session))
    } catch (error: Throwable) {
      fail(error)
    }
  }

  private fun fail(error: Throwable) {
    rethrowIfFatal(error)
    logger?.e(error) { "Map surface failed" }
    if (!failed.compareAndSet(expectedValue = false, newValue = true)) return
    runCatching { renderer.close() }.onFailure { logger?.e(it) { "Map renderer failed to close" } }
  }

  override fun close() {
    if (closed) return
    closed = true
    requestFrame = {}
    clearPresentation()
    val host = host ?: return
    runCatching {
      session?.let(renderer::onSurfaceLost)
      host.close()
      session = null
    }
      .onFailure { logger?.e(it) { "Map surface failed to close" } }
  }

  private class CompletedPresentation(
    val target: MlnFfiRenderTarget,
    val projection: MlnFfiMapFrameProjection?,
  ) : AutoCloseable {
    var anchor = target.extent.centerPresentationAnchor()
    var presented = false

    override fun close() {
      projection?.close()
    }
  }
}

private class MlnFfiMapHostSessionImpl(
  private val host: MlnFfiMapHost,
  private val closed: () -> Boolean,
  private val onRequestFrame: () -> Unit,
) : MlnFfiMapHostSession {
  override val isClosed: Boolean
    get() = closed()

  override val backends: RenderBackendPair
    get() = host.backends

  override fun requestFrame() {
    onRequestFrame()
  }

  override fun <T> withRendererAccess(action: () -> T): T = host.withRendererAccess(action)

  override fun enqueueRenderer(action: () -> Unit): Boolean = host.enqueueRenderer(action)
}

/** Aligns [sourceAnchor] with [destinationAnchor] without scaling [extent]. */
private fun presentationDestination(
  extent: MapExtent,
  sourceAnchor: MlnFfiMapPresentationAnchor,
  destinationAnchor: MlnFfiMapPresentationAnchor,
): MlnFfiMapDestination {
  return MlnFfiMapDestination(
    left = destinationAnchor.x - sourceAnchor.x,
    top = destinationAnchor.y - sourceAnchor.y,
    width = extent.physicalWidth,
    height = extent.physicalHeight,
  )
}
