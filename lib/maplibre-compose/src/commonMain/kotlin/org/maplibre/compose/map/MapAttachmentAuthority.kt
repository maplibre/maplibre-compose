package org.maplibre.compose.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.structuralEqualityPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.Viewport
import org.maplibre.compose.camera.internal.CameraCommandGuard
import org.maplibre.compose.camera.internal.CameraInputAuthority

/**
 * Tracks the current [MapAttachment] of one [MapState], hands out attachments and viewports to
 * callers that wait for them, and replays the durable camera onto each attachment. The lifecycle
 * authority drives it through the commit and invalidate calls.
 */
internal class MapAttachmentAuthority(
  internal val lifecycle: MapLifecycleAuthority,
  internal val gestureAuthority: CameraInputAuthority,
  private val styleAuthority: MapStyleAuthority,
  cameraPosition: CameraPosition,
) {
  private var cameraCommandRevision = 0L
  private val eventsFlow =
    MutableSharedFlow<MapEvent>(
      extraBufferCapacity = 64,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
  private var closedState: Boolean by mutableStateOf(false)
  private var cameraPositionState: CameraPosition by
    mutableStateOf(cameraPosition, structuralEqualityPolicy())

  val cameraPosition: CameraPosition
    get() = cameraPositionState

  val isClosed: Boolean
    get() = closedState

  val events: Flow<MapEvent> = eventsFlow.asSharedFlow()

  var current: MapAttachment? by mutableStateOf(null)
    private set

  /** What callers waiting for an attachment or viewport observe. Updated after [current]. */
  private val presence = MutableStateFlow(Presence())

  fun setCameraPosition(position: CameraPosition) {
    lifecycle.requireMain()
    val guard = gestureAuthority.beginProgrammatic()
    requireOpen()
    if (!guard.isValid()) return
    cameraPositionState = position
    cameraCommandRevision++
    val attachment = current ?: return
    applyAttachmentCameraCommand(
      attachment,
      CameraCommand(attachment.adapter, position, cameraCommandRevision, guard),
    )
  }

  suspend fun awaitViewport(): Viewport {
    requireOpen()
    return presence
      .first {
        if (it.closed) throw CancellationException("The map closed while waiting for a viewport")
        it.viewport != null
      }
      .viewport!!
  }

  /** Waits for the first viewport of [attachment], or fails once it is no longer current. */
  suspend fun awaitViewport(attachment: MapAttachment): Viewport =
    presence
      .first {
        if (it.attachment !== attachment) throw MapAttachmentChangedException()
        it.viewport != null
      }
      .viewport!!

  /**
   * Publishes the camera and viewport of [adapter] and returns the presentation that holds them. A
   * map with no readable viewport keeps the values it has, so a caller that reacts to a camera
   * event still reaches its presentation.
   */
  internal fun synchronizeCamera(adapter: MapAdapter): MapAttachment? {
    lifecycle.requireMain()
    if (!lifecycle.acceptsPresentation(adapter)) return null
    val cameraPosition = adapter.getCameraPosition()
    val viewport = adapter.getViewport()
    return run {
      if (!lifecycle.acceptsPresentation(adapter)) return@run null
      val current = current ?: return@run null
      if (viewport != null) {
        cameraPositionState = cameraPosition
        current.updateViewport(viewport)
      }
      current
    }
  }

  /**
   * Reacts to one engine event that the lifecycle already accepted, then publishes it to [events].
   * Ignores an [adapter] that this state no longer accepts. Publication follows the reaction, so a
   * collector reads the values that the event produced.
   */
  internal fun onEvent(adapter: MapAdapter, event: MapEvent) {
    lifecycle.requireMain()
    val accepted =
      when (event) {
        is MapEvent.CameraMoveStarted ->
          synchronizeCamera(adapter)?.also { it.cameraChangeStarted() } != null
        MapEvent.CameraMoved -> synchronizeCamera(adapter) != null
        is MapEvent.CameraMoveEnded ->
          synchronizeCamera(adapter)?.also { it.cameraChangeEnded() } != null
        is MapEvent.FrameRendered -> lifecycle.acceptsPresentation(adapter)
        MapEvent.StyleLoaded,
        is MapEvent.StyleLoadFailed,
        is MapEvent.SourceDataFailed,
        MapEvent.Idle -> lifecycle.acceptsAdapter(adapter)
      }
    if (accepted) eventsFlow.tryEmit(event)
  }

  /** Reports whether a gesture holds the camera of [adapter]. */
  internal fun setGestureActive(adapter: MapAdapter, active: Boolean) {
    lifecycle.requireMain()
    presentedAttachment(adapter)?.setGestureActive(active)
  }

  /** Reports the engagement of the input node over [adapter]. */
  internal fun setEngaged(adapter: MapAdapter, engaged: Boolean) {
    lifecycle.requireMain()
    presentedAttachment(adapter)?.setEngaged(engaged)
  }

  /** Ends camera changes that the engine behind [adapter] will never finish. */
  internal fun endCameraChange(adapter: MapAdapter) {
    lifecycle.requireMain()
    presentedAttachment(adapter)?.abandonCameraChanges()
  }

  private fun presentedAttachment(adapter: MapAdapter): MapAttachment? = run {
    if (!lifecycle.acceptsPresentation(adapter)) return@run null
    current
  }

  internal fun isCurrent(candidate: MapAttachment): Boolean =
    current === candidate && lifecycle.isCurrent(candidate.token, candidate.adapter)

  internal fun <T> withCurrentOrNull(candidate: MapAttachment, block: () -> T): T? {
    if (!isCurrent(candidate)) return null
    val result = block()
    return result.takeIf { isCurrent(candidate) }
  }

  private fun requireOpen() {
    check(!lifecycle.isClosed) { "The map state is closed" }
  }

  internal fun commitClosed() {
    lifecycle.requireMain()
    styleAuthority.invalidateForClose()
    val outgoing = current
    Snapshot.withMutableSnapshot {
      closedState = true
      current = null
      outgoing?.invalidate()
    }
    presence.value = Presence(closed = true)
    outgoing?.cancelLeaseBoundOperations()
  }

  internal fun invalidatePresentation(adapter: MapAdapter?) {
    lifecycle.requireMain()
    val outgoing = current
    Snapshot.withMutableSnapshot {
      current = null
      outgoing?.invalidate()
      if (adapter?.retainsEngineBetweenPresentations != true) {
        styleAuthority.style.loadState = StyleLoadState.Pending
      }
    }
    presence.value = Presence()
    outgoing?.cancelLeaseBoundOperations()
  }

  /**
   * Posted from the thread that closed [adapter], so it can run after a replacement was published.
   * The style then belongs to the replacement and stays.
   */
  internal fun invalidateClosedAdapter(adapter: MapAdapter) {
    lifecycle.requireMain()
    val outgoing = current?.takeIf { it.adapter === adapter }
    val replaced = lifecycle.currentAdapter().let { it != null && it !== adapter }
    Snapshot.withMutableSnapshot {
      if (!replaced) styleAuthority.invalidateClosedAdapter()
      if (outgoing != null) {
        current = null
        outgoing.invalidate()
      }
    }
    if (outgoing != null) presence.value = Presence()
    outgoing?.cancelLeaseBoundOperations()
  }

  internal fun configurePresentationAdapter(adapter: MapAdapter) {
    lifecycle.requireMain()
    val camera = run {
      if (!lifecycle.isPendingPublication(adapter)) return
      CameraCommand(adapter, cameraPositionState, cameraCommandRevision)
    }
    applyCameraCommand(camera)
    styleAuthority.configurePendingAdapter(adapter)
  }

  internal fun seedPresentationViewport(token: MapPresentationToken, adapter: MapAdapter) {
    lifecycle.requireMain()
    val viewport = adapter.getViewport() ?: return
    run {
      val current = current ?: return@run
      if (current.token != token || current.adapter !== adapter || current.viewport != null) return
      current.updateViewport(viewport)
    }
  }

  private fun applyCameraCommand(initial: CameraCommand) {
    var command = initial
    while (true) {
      if (lifecycle.currentAdapter() !== command.adapter) return
      command.adapter.setCameraPosition(command.value, command.guard)
      // The adapter can re-enter setCameraPosition synchronously; replay the newest value after.
      if (lifecycle.currentAdapter() !== command.adapter) return
      if (cameraCommandRevision == command.revision) return
      command =
        CameraCommand(command.adapter, cameraPositionState, cameraCommandRevision, command.guard)
    }
  }

  private fun applyAttachmentCameraCommand(
    attachment: MapAttachment,
    initial: CameraCommand,
  ) {
    val guard = CameraCommandGuard {
      isCurrent(attachment) && initial.guard?.isValid() != false
    }
    var command = initial
    while (true) {
      if (!lifecycle.isCurrent(attachment.token, command.adapter)) return
      command.adapter.setCameraPosition(command.value, guard)
      if (!lifecycle.isCurrent(attachment.token, command.adapter)) return
      if (cameraCommandRevision == command.revision) return
      command =
        CameraCommand(command.adapter, cameraPositionState, cameraCommandRevision, command.guard)
    }
  }

  internal fun commitPresentation(
    token: MapPresentationToken,
    adapter: MapAdapter,
  ) {
    lifecycle.requireMain()
    val attachment = MapAttachment(this, token, adapter)
    current = attachment
    presence.value = Presence(attachment)
  }

  suspend fun awaitAttachment(): MapAttachment {
    requireOpen()
    return presence
      .first {
        if (it.closed) throw CancellationException("The map closed while waiting for an attachment")
        it.attachment != null
      }
      .attachment!!
  }

  internal fun viewportPublished(attachment: MapAttachment, viewport: Viewport?) {
    lifecycle.requireMain()
    presence.update { if (it.attachment === attachment) it.copy(viewport = viewport) else it }
  }

  private data class Presence(
    val attachment: MapAttachment? = null,
    val viewport: Viewport? = null,
    val closed: Boolean = false,
  )

  private data class CameraCommand(
    val adapter: MapAdapter,
    val value: CameraPosition,
    val revision: Long,
    val guard: CameraCommandGuard? = null,
  )

  private data class AttachmentCameraCommand(
    val attachment: MapAttachment,
    val command: CameraCommand,
  )
}
