package org.maplibre.compose.mlnffi

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.ComposeMapSurface
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.map.mapSurface
import org.maplibre.compose.util.rethrowIfFatal

private val frameClockOrigin = TimeSource.Monotonic.markNow()

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

internal class MlnFfiSurfaceController(
  private val renderer: MlnFfiMapRenderer,
  private val hostResult: MlnFfiMapHostResult,
  private val logger: MapLog?,
) : ComposeMapSurface {
  private val host = (hostResult as? MlnFfiMapHostResult.Created)?.host
  private var session: MlnFfiMapHostSession? = null
  @Volatile private var requestFrame: () -> Unit = {}
  private var enabled = true
  private var failed = false
  private var rendererClosed = false
  private var closed = false
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
        val session = MlnFfiMapHostSessionImpl(hostResult.host) { this.requestFrame() }
        this.session = session
        try {
          renderer.onSurfaceAvailable(session)
          requestFrame()
        } catch (error: Throwable) {
          fail(error)
        }
      }
    }
  }

  override fun detach() {
    requestFrame = {}
  }

  override fun prepare(extent: MapExtent): Boolean {
    val host = host ?: return false
    if (closed || failed || !enabled || extent.isEmpty) return false
    val frameId = nextFrameId++
    try {
      if (configuredExtent != extent) {
        host.resize(extent)
        renderer.onSurfaceChanged(extent)
        configuredExtent = extent
      }
      val acquired =
        host.acquireFrame(frameId, extent, frameClockOrigin.elapsedNow().inWholeNanoseconds)
      if (acquired == MlnFfiMapFrameAcquisition.NotReady) {
        requestFrame()
        return false
      }
      val frame = (acquired as MlnFfiMapFrameAcquisition.Acquired).frame
      var candidate: CompletedPresentation? = null
      try {
        host.withProducerAccess(frame) {
          when (val result = renderer.render(frame, captureProjection = true)) {
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
    if (closed || failed || !enabled || extent.isEmpty || completed == null) {
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
    if (!closed && !failed && completed != null && destination != null) {
      try {
        drew = host?.draw(scope, completed.target, destination) == true
        if (drew && !completed.presented) {
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
    if (error !is MlnFfiRecoverableFrameException || ++failures > MAX_RECOVERY_ATTEMPTS) {
      fail(error)
      return
    }
    logger?.w(error) {
      "Map frame $frameId failed; rebuilding the render session (attempt $failures of $MAX_RECOVERY_ATTEMPTS)"
    }
    try {
      renderer.onSurfaceLost()
      renderer.onSurfaceAvailable(checkNotNull(session))
      requestFrame()
    } catch (error: Throwable) {
      fail(error)
    }
  }

  private fun fail(error: Throwable) {
    rethrowIfFatal(error)
    failed = true
    logger?.e(error) { "Map surface failed" }
    if (rendererClosed) return
    rendererClosed = true
    runCatching { renderer.close() }.onFailure { logger?.e(it) { "Map renderer failed to close" } }
  }

  override fun close() {
    if (closed) return
    closed = true
    requestFrame = {}
    clearPresentation()
    if (host != null) {
      runCatching { renderer.onSurfaceLost() }
        .onFailure { logger?.e(it) { "Map renderer failed to release the surface" } }
      runCatching { host?.close() }.onFailure { logger?.e(it) { "Map host failed to close" } }
    }
    session = null
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

  private companion object {
    const val MAX_RECOVERY_ATTEMPTS = 3
  }
}

private class MlnFfiMapHostSessionImpl(
  private val host: MlnFfiMapHost,
  private val onRequestFrame: () -> Unit,
) : MlnFfiMapHostSession {
  override val backends: RenderBackendPair
    get() = host.backends

  override fun requestFrame() {
    onRequestFrame()
  }

  override fun <T> withRendererAccess(action: () -> T): T = host.withRendererAccess(action)

  override fun enqueueRenderer(action: () -> Unit): Boolean = host.enqueueRenderer(action)
}
