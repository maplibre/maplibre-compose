package org.maplibre.compose.camera.internal

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maplibre.compose.interaction.BearingSnapping
import org.maplibre.compose.interaction.HapticEmphasis
import org.maplibre.compose.interaction.internal.BearingHapticDetector
import org.maplibre.compose.interaction.internal.CameraComponent
import org.maplibre.compose.interaction.internal.CameraConfiguration
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MapAttachment
import org.maplibre.compose.map.MapState

internal typealias CameraInputToken = CameraInputAuthority.Token

internal fun interface CameraCommandGuard {
  fun isValid(): Boolean

  /** Waits for older commands to be dispatched, without waiting for their animations. */
  suspend fun awaitDispatchTurn() = Unit

  /** Called once the command has entered the backend queue (or has been rejected). */
  fun dispatched() = Unit
}

/**
 * Activation may stop transitions or report state, invoking callbacks that replace this command.
 */
internal inline fun runCameraCommand(
  token: CameraInputToken?,
  guard: CameraCommandGuard? = null,
  activate: () -> Unit,
  command: () -> Unit,
): Boolean {
  if (token?.canExecute == false || guard?.isValid() == false) return false
  activate()
  if (token?.canExecute == false || guard?.isValid() == false) return false
  command()
  return true
}

/**
 * Admission and takeover run on the main thread. Execution validity is read, and completion is
 * reported, on the thread that runs a camera command, so that state sits behind [fence].
 */
internal class CameraInputAuthority(private val owner: MapState) {
  private val fence = reentrantLock()
  private var dispatchTail: DispatchTurn? = null
  private var commandRevision = 0L
  private var cameraGeneration = 0L
  private var inputGeneration = 0L
  private var active: CameraInputToken? = null
  private val programmaticJobs = mutableSetOf<Job>()
  private var configuration = CameraConfiguration()

  /** Callback replacements do not revoke input. Resolved policy and momentum changes do. */
  fun updateConfiguration(value: CameraConfiguration) {
    owner.lifecycle.requireMain()
    val previous = fence.withLock {
      val changed = configuration.settings != value.settings
      configuration = value
      if (changed) {
        inputGeneration++
        revokeActive()
      } else null
    }
    previous?.cancelWork()
  }

  val generation: Long
    get() = fence.withLock { inputGeneration }

  fun acquire(
    adapter: MapAdapter,
    expectedInputGeneration: Long? = null,
  ): CameraInputToken {
    owner.lifecycle.requireMain()
    var previous: CameraInputToken? = null
    var previousJobs: List<Job> = emptyList()
    val token = fence.withLock {
      val attachment = owner.currentMapAttachment
      val target = attachment?.adapter as? CameraInputTarget
      val ready =
        attachment != null &&
          owner.attachmentAuthority.isCurrent(attachment) &&
          attachment.viewport != null &&
          target?.isGestureReady == true &&
          adapter === attachment.adapter &&
          (expectedInputGeneration == null || expectedInputGeneration == inputGeneration)
      val token = Token(attachment, target, ready)
      if (!ready) {
        return@withLock token
      }
      previous = revokeActive()
      previousJobs = programmaticJobs.toList()
      programmaticJobs.clear()
      dispatchTail = null
      cameraGeneration++
      inputGeneration++
      active = token
      token
    }
    previousJobs.forEach { it.cancel(CancellationException("A newer input owns the camera")) }
    previous?.cancelWork()
    return token
  }

  /** A delayed click may acquire camera authority only if no newer accepted input intervened. */
  fun acquireIfCurrent(adapter: MapAdapter, generation: Long): CameraInputToken? =
    acquire(adapter, expectedInputGeneration = generation).takeIf { it.acceptsCommands }

  /** Even input with no camera response invalidates an older click's camera fallthrough. */
  fun observeInput(): Long = fence.withLock { ++inputGeneration }

  fun beginProgrammatic(
    job: Job? = null,
    concurrent: Boolean = false,
    supersededByAnyCommand: Boolean = false,
  ): CameraCommandGuard {
    owner.lifecycle.requireMain()
    var previous: CameraInputToken? = null
    var previousJobs: List<Job> = emptyList()
    var revision = 0L
    var turn: DispatchTurn? = null
    val generation = fence.withLock {
      job?.ensureActive()
      check(!owner.isClosed) { "The map state is closed" }
      previous = revokeActive()
      revision = ++commandRevision
      if (!concurrent) {
        previousJobs = programmaticJobs.toList()
        programmaticJobs.clear()
        cameraGeneration++
        dispatchTail = null
      }
      if (job != null) {
        turn = DispatchTurn(dispatchTail).also { dispatchTail = it }
      }
      job?.let(programmaticJobs::add)
      inputGeneration++
      cameraGeneration
    }
    // Turns complete and jobs finish on whichever thread completes them; bookkeeping is main's.
    turn?.onCompletion {
      owner.lifecycle.postToMain {
        fence.withLock { if (dispatchTail === turn) dispatchTail = null }
      }
    }
    previousJobs.forEach { it.cancel(CancellationException("A newer command owns the camera")) }
    job?.invokeOnCompletion {
      owner.lifecycle.postToMain {
        turn?.release()
        fence.withLock { programmaticJobs.remove(job) }
      }
    }
    previous?.cancelWork()
    return object : CameraCommandGuard {
      override fun isValid(): Boolean = fence.withLock {
        !owner.isClosed &&
          cameraGeneration == generation &&
          job?.isCancelled != true &&
          (!supersededByAnyCommand || commandRevision == revision)
      }

      override suspend fun awaitDispatchTurn() {
        turn?.await()
      }

      override fun dispatched() {
        turn?.release()
      }
    }
  }

  /** Orders dispatch, not animation completion. Cancelled entries cannot bypass a predecessor. */
  private class DispatchTurn(private val previous: DispatchTurn?) {
    private val completion = CompletableDeferred<Unit>()

    suspend fun await() {
      previous?.completion?.await()
    }

    fun onCompletion(block: () -> Unit) {
      completion.invokeOnCompletion { block() }
    }

    fun release() {
      val predecessor = previous?.completion
      if (predecessor == null) completion.complete(Unit)
      else predecessor.invokeOnCompletion { completion.complete(Unit) }
    }
  }

  /** Called while invalidating the attachment; cancellation callbacks run outside the fence. */
  fun detach(attachment: MapAttachment) {
    owner.lifecycle.requireMain()
    val token =
      fence.withLock {
        inputGeneration++
        active?.takeIf { it.attachment === attachment }?.also { revokeActive() }
      } ?: return
    owner.runtime.physicalScope.launch { token.cancelWork() }
  }

  private fun revokeActive(): Token? = fence.withLock { active?.also { it.revoke() } }

  private enum class Status {
    Open,
    Sealed,
    Cancelled,
    Completed,
  }

  /** State and its fence stay together; backends only queue, execute, and finish. */
  inner class Token
  internal constructor(
    val attachment: MapAttachment?,
    private val target: CameraInputTarget?,
    ready: Boolean,
  ) {
    private var status = if (ready) Status.Open else Status.Cancelled
    @Volatile private var job: Job? = null
    private var finishQueued = false
    private val completion = CompletableDeferred<Unit>().also { if (!ready) it.complete(Unit) }
    private val startedComponents = mutableSetOf<CameraComponent>()
    private var rotated = false
    private var hapticDetector: BearingHapticDetector? = null
    private var onHaptic: ((HapticEmphasis) -> Unit)? = null
    private val hapticClock = TimeSource.Monotonic.markNow()

    fun setHapticFeedback(callback: (HapticEmphasis) -> Unit) {
      fence.withLock { onHaptic = callback }
    }

    /** Called after a direct rotation executes; callbacks run outside the fence. */
    fun reportRotation(from: Double, to: Double) {
      val event = fence.withLock {
        if (!accepts(enqueue = false)) return@withLock null
        val callback = onHaptic ?: return@withLock null
        val detector =
          hapticDetector
            ?: BearingHapticDetector(configuration.settings.rotate.haptics).also {
              hapticDetector = it
            }
        detector.update(from, to, hapticClock.elapsedNow())?.let { callback to it }
      }
      event?.let { (callback, emphasis) -> callback(emphasis) }
    }

    val bearingSnapping: BearingSnapping?
      get() = fence.withLock {
        configuration.settings.rotate.snapping.takeIf {
          accepts(enqueue = true) && rotated && it.enabled
        }
      }

    val acceptsCommands: Boolean
      get() = accepts(enqueue = true)

    val canExecute: Boolean
      get() = accepts(enqueue = false)

    val isCancelled: Boolean
      get() = fence.withLock { status == Status.Cancelled }

    fun permitted(component: CameraComponent): Boolean = fence.withLock {
      accepts(enqueue = true) && configuration.settings.enabled(component)
    }

    /** Application callbacks run outside the fence and may replace this camera operation. */
    fun prepare(component: CameraComponent): Boolean {
      val start = fence.withLock {
        if (!accepts(enqueue = true) || !configuration.settings.enabled(component)) return false
        if (component == CameraComponent.Rotate) rotated = true
        if (startedComponents.add(component)) configuration.onStart[component] else null
      }
      start?.invoke()
      return permitted(component)
    }

    fun rearm(component: CameraComponent) = fence.withLock {
      startedComponents.remove(component)
      if (component == CameraComponent.Rotate) hapticDetector = null
    }

    fun registerJob(value: Job) {
      val cancelled = fence.withLock {
        job = value
        status == Status.Cancelled
      }
      if (cancelled) value.cancel(CancellationException("A newer input owns the camera"))
    }

    fun enqueue(action: () -> Unit): Boolean = fence.withLock {
      if (!accepts(enqueue = true)) return false
      action()
      true
    }

    fun finish(cancelled: Boolean, enqueue: () -> Unit): Unit = fence.withLock {
      if (status == Status.Completed) return
      if (cancelled) revoke() else if (status == Status.Open) status = Status.Sealed
      if (!finishQueued) {
        finishQueued = true
        enqueue()
      }
    }

    fun complete() = fence.withLock {
      if (status != Status.Cancelled) status = Status.Completed
      if (active === this) active = null
      completion.complete(Unit)
    }

    suspend fun awaitCompletion() = completion.await()

    fun revoke() = fence.withLock {
      status = Status.Cancelled
      if (active === this) active = null
    }

    fun cancelWork() {
      job?.cancel(CancellationException("A newer input owns the camera"))
      target?.cancelGesture(this)
    }

    private fun accepts(enqueue: Boolean): Boolean = fence.withLock {
      active === this &&
        attachment?.let(owner.attachmentAuthority::isCurrent) == true &&
        target?.isGestureReady == true &&
        job?.isActive != false &&
        (if (enqueue) status == Status.Open else status == Status.Open || status == Status.Sealed)
    }
  }
}
