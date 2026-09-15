package org.maplibre.compose.mlnffi

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.Channel
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.map.MapExtent
import org.maplibre.compose.util.rethrowIfFatal

/** The origin the frame clock counts from, fixed for the process so hosts can compare frames. */
private val frameClockOrigin = TimeSource.Monotonic.markNow()

/** Hosts [renderer] on a Compose drawing surface, driving the frame loop. */
@Composable
internal fun MlnFfiMapSurface(
  renderer: MlnFfiMapRenderer,
  hostResult: MlnFfiMapHostResult,
  modifier: Modifier = Modifier,
  logger: MapLog? = null,
  presentFrames: Boolean = true,
) {
  var frameRequest by remember { mutableLongStateOf(0L) }
  val requests = remember(renderer, hostResult) { Channel<Unit>(Channel.CONFLATED) }
  var failed by remember(renderer, hostResult) { mutableStateOf(false) }
  val drawState = remember(renderer, hostResult) { MlnFfiMapDrawState() }
  val host = (hostResult as? MlnFfiMapHostResult.Created)?.host
  val session =
    remember(host, requests) {
      host?.let { MlnFfiMapHostSessionImpl(it) { requests.trySend(Unit) } }
    }

  LaunchedEffect(requests) {
    for (request in requests) {
      withFrameNanos { frameRequest += 1 }
    }
  }

  DisposableEffect(hostResult, renderer, session) {
    when (hostResult) {
      is MlnFfiMapHostResult.Created -> {
        checkNotNull(session)
        try {
          renderer.onSurfaceAvailable(session)
          session.requestFrame()
        } catch (error: Throwable) {
          rethrowIfFatal(error)
          failed = true
          logger?.e(error) { "Map renderer failed to take the host surface" }
          drawState.closeRenderer(renderer, logger)
        }
      }
      is MlnFfiMapHostResult.Failed -> {
        failed = true
        logger?.e(hostResult.cause) { hostResult.diagnostic }
        drawState.closeRenderer(renderer, logger)
      }
    }

    onDispose {
      if (host != null) {
        // Drop render-session references before the host frees its targets.
        runCatching { renderer.onSurfaceLost() }
          .onFailure { logger?.e(it) { "Map renderer failed to release the host surface" } }
        runCatching { host.close() }.onFailure { logger?.e(it) { "Map host failed to close" } }
      }
      drawState.reset()
    }
  }

  var preparedFrame by
    remember(renderer, hostResult) {
      mutableStateOf<MlnFfiPreparedPresentation?>(null)
    }

  fun prepare(frameExtent: MapExtent) {
    if (!presentFrames || host == null || session == null || frameExtent.isEmpty || failed) {
      preparedFrame = null
      return
    }
    val frameId = drawState.nextFrameId()
    var rendered = false
    try {
      if (drawState.configuredExtent != frameExtent) {
        host.resize(frameExtent)
        renderer.onSurfaceChanged(frameExtent)
        drawState.configuredExtent = frameExtent
        session.requestFrame()
      }
      when (
        val acquisition =
          host.acquireFrame(
            frameId,
            frameExtent,
            frameClockOrigin.elapsedNow().inWholeNanoseconds,
          )
      ) {
        MlnFfiMapFrameAcquisition.NotReady -> session.requestFrame()
        is MlnFfiMapFrameAcquisition.Acquired -> {
          val frame = acquisition.frame
          try {
            val completed =
              host.withProducerAccess(frame) {
                val result = renderer.render(frame)
                val anchor = renderer.presentationAnchor(frame.extent)
                if (result == MlnFfiFrameResult.RENDERED) {
                  val projection = renderer.captureFrameProjection(frame.extent)
                  val renderedAnchor = projection?.anchor ?: anchor
                  drawState.recordPresentationAnchor(frame.extent, renderedAnchor)
                  MlnFfiMapCompletedPresentation(frame.target, renderedAnchor, projection)
                } else {
                  drawState.recordResizedPresentationAnchor(frame.extent, anchor)
                  null
                }
              }
            if (completed != null) {
              try {
                host.completeProducerAccess(frame)
              } catch (error: Throwable) {
                completed.projection?.close()
                throw error
              }
              drawState.lastCompletedPresentation?.projection?.close()
              drawState.lastCompletedPresentation = completed
              rendered = true
            }
          } finally {
            runCatching { host.releaseFrame(frame) }
              .onFailure { logger?.e(it) { "Map host failed to release frame $frameId" } }
          }
        }
      }
      preparedFrame =
        drawState.lastCompletedPresentation?.let { completed ->
          val destination =
            presentationDestination(
              extent = completed.target.extent,
              sourceAnchor = completed.anchor,
              destinationAnchor = drawState.presentationAnchor(frameExtent),
            )
          completed.projection?.present(destination, frameExtent.scaleFactor)
          MlnFfiPreparedPresentation(completed.target, destination, frameId, rendered)
        }
    } catch (error: Throwable) {
      rethrowIfFatal(error)
      preparedFrame = null
      if (!recoverFromFrameFailure(renderer, session, drawState, frameId, error, logger)) {
        failed = true
        drawState.closeRenderer(renderer, logger)
      }
    }
  }

  Canvas(
    modifier =
      modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val extent = MapExtent.fromPhysical(placeable.width, placeable.height, density.toDouble())
        // Prepare during measurement, before Compose starts placing overlay children.
        frameRequest
        Snapshot.withoutReadObservation { prepare(extent) }
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
      }
  ) {
    val prepared = preparedFrame
    var drew = false
    if (prepared != null && host != null && session != null && !failed) {
      try {
        drew = host.draw(this, prepared.target, prepared.destination)
        if (prepared.rendered) drawState.onFrameSucceeded()
      } catch (error: Throwable) {
        rethrowIfFatal(error)
        preparedFrame = null
        if (
          !recoverFromFrameFailure(renderer, session, drawState, prepared.frameId, error, logger)
        ) {
          failed = true
          drawState.closeRenderer(renderer, logger)
        }
      }
    }
    if (!drew) drawRect(Color.Transparent)
  }
}

private const val MAX_FRAME_RECOVERY_ATTEMPTS = 3

/** Rebuilds after an explicitly recoverable graphics failure, or reports that recovery is done. */
private fun recoverFromFrameFailure(
  renderer: MlnFfiMapRenderer,
  session: MlnFfiMapHostSession,
  drawState: MlnFfiMapDrawState,
  frameId: Long,
  error: Throwable,
  logger: MapLog?,
): Boolean {
  if (error !is MlnFfiRecoverableFrameException) {
    logger?.e(error) { "Map frame $frameId failed with an unrecoverable error" }
    return false
  }

  val attempt = drawState.recordFrameFailure()
  if (attempt > MAX_FRAME_RECOVERY_ATTEMPTS) {
    logger?.e(error) {
      "Map frame $frameId failed after $MAX_FRAME_RECOVERY_ATTEMPTS recovery attempts"
    }
    return false
  }

  logger?.w(error) {
    "Map frame $frameId failed; rebuilding the render session " +
      "(attempt $attempt of $MAX_FRAME_RECOVERY_ATTEMPTS)"
  }
  drawState.lastCompletedPresentation?.projection?.close()
  drawState.lastCompletedPresentation = null
  try {
    renderer.onSurfaceLost()
  } catch (releaseError: Throwable) {
    rethrowIfFatal(releaseError)
    logger?.e(releaseError) { "Map renderer failed to release the lost surface" }
    return false
  }

  return try {
    renderer.onSurfaceAvailable(session)
    session.requestFrame()
    true
  } catch (rearmError: Throwable) {
    rethrowIfFatal(rearmError)
    logger?.e(rearmError) { "Map renderer failed to take the surface back after frame $frameId" }
    false
  }
}

private class MlnFfiMapDrawState {
  private var nextFrameId = 1L
  private var rendererClosed = false

  var lastCompletedPresentation: MlnFfiMapCompletedPresentation? = null
  var configuredExtent: MapExtent = MapExtent.Empty
  private var presentationExtent: MapExtent = MapExtent.Empty
  private var currentPresentationAnchor: MlnFfiMapPresentationAnchor? = null

  var frameFailures: Int = 0
    private set

  fun nextFrameId(): Long = nextFrameId++

  fun recordFrameFailure(): Int = ++frameFailures

  fun onFrameSucceeded() {
    frameFailures = 0
  }

  fun closeRenderer(renderer: MlnFfiMapRenderer, logger: MapLog?) {
    if (rendererClosed) return
    rendererClosed = true
    runCatching { renderer.close() }.onFailure { logger?.e(it) { "Map renderer failed to close" } }
  }

  fun reset() {
    lastCompletedPresentation?.projection?.close()
    lastCompletedPresentation = null
    configuredExtent = MapExtent.Empty
    presentationExtent = MapExtent.Empty
    currentPresentationAnchor = null
    frameFailures = 0
    rendererClosed = false
  }

  fun recordResizedPresentationAnchor(extent: MapExtent, anchor: MlnFfiMapPresentationAnchor) {
    if (presentationExtent != extent) recordPresentationAnchor(extent, anchor)
  }

  fun recordPresentationAnchor(extent: MapExtent, anchor: MlnFfiMapPresentationAnchor) {
    presentationExtent = extent
    currentPresentationAnchor = anchor
  }

  fun presentationAnchor(extent: MapExtent): MlnFfiMapPresentationAnchor =
    currentPresentationAnchor?.takeIf { presentationExtent == extent }
      ?: extent.centerPresentationAnchor()
}

private data class MlnFfiMapCompletedPresentation(
  val target: MlnFfiRenderTarget,
  val anchor: MlnFfiMapPresentationAnchor,
  val projection: MlnFfiMapFrameProjection?,
)

private data class MlnFfiPreparedPresentation(
  val target: MlnFfiRenderTarget,
  val destination: MlnFfiMapDestination,
  val frameId: Long,
  val rendered: Boolean,
)

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
