@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The platform transaction behind a Compose map surface. Accessed on the Compose thread. */
internal interface ComposeMapSurface {
  val maximumFps: Int?

  /** Connects a node, including reattachment after a temporary [detach]. */
  fun attach(requestFrame: () -> Unit)

  /** Disconnects scheduling without releasing the composition-owned platform resources. */
  fun detach()

  fun setPresentFrames(value: Boolean)

  /**
   * Returns true only when a new image completed. Requests made here belong to the next attempt.
   */
  fun prepare(extent: MapExtent): Boolean

  /**
   * Selects the geometry shared by drawing and overlay projection, even when an image is retained.
   */
  fun present(extent: MapExtent)

  fun draw(scope: DrawScope)

  /** Releases resources when the owning composition is disposed. */
  fun close()
}

/** Frame pacing shared by frame-clock and platform-queue drivers. */
internal class MapFramePacer(private val followsFrameClock: Boolean = false) {
  private var lastRender: TimeMark? = null

  fun remaining(maximumFps: Int?): Duration {
    val last = lastRender ?: return Duration.ZERO
    val fps = maximumFps?.takeIf { it > 0 } ?: return Duration.ZERO
    // Frame-clock drivers need tolerance at the display cadence; timer queues use the full cap.
    val interval = if (followsFrameClock) 0.9 / fps else 1.0 / fps
    return (interval.seconds - last.elapsedNow()).coerceAtLeast(Duration.ZERO)
  }

  fun rendered(start: TimeMark) {
    lastRender = start
  }
}

/** A wake-up channel carries no work count. Dirtiness is consumed only when preparation starts. */
private class MapFrameRequests : AutoCloseable {
  private val dirty = AtomicBoolean(true)
  private val closed = AtomicBoolean(false)
  private val wakes = Channel<Unit>(Channel.CONFLATED)

  fun request() {
    if (closed.load()) return
    dirty.store(true)
    wakes.trySend(Unit)
  }

  val pending: Boolean
    get() = dirty.load()

  fun consume(): Boolean = dirty.exchange(false)

  suspend fun awaitWake(): Boolean = wakes.receiveCatching().isSuccess

  override fun close() {
    closed.store(true)
    dirty.store(false)
    wakes.close()
  }
}

internal fun Modifier.mapSurface(surface: ComposeMapSurface, presentFrames: Boolean): Modifier =
  then(MapSurfaceElement(surface, presentFrames))

private data class MapSurfaceElement(val surface: ComposeMapSurface, val presentFrames: Boolean) :
  ModifierNodeElement<MapSurfaceNode>() {
  override fun create() = MapSurfaceNode(surface, presentFrames)

  override fun update(node: MapSurfaceNode) {
    node.update(surface, presentFrames)
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "mapSurface"
  }
}

private class MapSurfaceNode(
  private var surface: ComposeMapSurface,
  private var presentFrames: Boolean,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode {
  private var requests = MapFrameRequests()
  private var pacer = MapFramePacer(followsFrameClock = true)
  private var wakeJob: Job? = null
  private var extent = MapExtent.Empty
  private var framePermitted = false

  override fun onAttach() {
    requests = MapFrameRequests()
    pacer = MapFramePacer(followsFrameClock = true)
    extent = MapExtent.Empty
    framePermitted = false
    val currentRequests = requests
    surface.setPresentFrames(presentFrames)
    surface.attach(currentRequests::request)
    wakeJob = coroutineScope.launch {
      while (currentRequests.awaitWake()) {
        if (!currentRequests.pending || framePermitted) continue
        while (currentRequests.pending) {
          val remaining = pacer.remaining(surface.maximumFps)
          if (remaining == Duration.ZERO) break
          // A setting change or a new request can shorten the deadline while we are waiting.
          withTimeoutOrNull(remaining) { currentRequests.awaitWake() }
        }
        withFrameNanos {
          if (currentRequests.pending) {
            framePermitted = true
            invalidateMeasurement()
          }
        }
      }
    }
  }

  fun update(next: ComposeMapSurface, presentFrames: Boolean) {
    this.presentFrames = presentFrames
    if (surface === next) {
      surface.setPresentFrames(presentFrames)
      return
    }
    if (isAttached) onDetach()
    surface = next
    if (isAttached) onAttach()
  }

  override fun onDetach() {
    requests.close()
    wakeJob?.cancel()
    wakeJob = null
    surface.detach()
  }

  override fun MeasureScope.measure(
    measurable: Measurable,
    constraints: Constraints,
  ): MeasureResult {
    val child = measurable.measure(constraints)
    val next = MapExtent.fromPhysical(child.width, child.height, density.toDouble())
    val resized = next != extent
    extent = next
    if (resized) requests.request()
    var attempted = false
    if (!next.isEmpty && (framePermitted || resized) && requests.pending) {
      framePermitted = false
      if (pacer.remaining(surface.maximumFps) == Duration.ZERO) {
        requests.consume()
        attempted = true
        val start = TimeSource.Monotonic.markNow()
        // Publish before sibling overlays are placed; drawing would be too late.
        if (Snapshot.withoutReadObservation { surface.prepare(next) }) pacer.rendered(start)
      } else requests.request()
    }
    Snapshot.withoutReadObservation { surface.present(next) }
    if (resized || attempted) invalidateDraw()
    return layout(child.width, child.height) { child.place(0, 0) }
  }

  override fun ContentDrawScope.draw() {
    surface.draw(this)
  }
}
