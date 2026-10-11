package org.maplibre.compose.camera.internal

import kotlin.time.TimeSource
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
import org.maplibre.compose.map.MapAttachmentChangedException
import org.maplibre.compose.map.MapState

internal typealias CameraInputToken = CameraInputAuthority.Token

internal fun interface CameraCommandGuard {
  fun isValid(): Boolean
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
  if (token?.acceptsCommands == false || guard?.isValid() == false) return false
  activate()
  if (token?.acceptsCommands == false || guard?.isValid() == false) return false
  command()
  return true
}

/** Camera admission, takeover, execution, and completion are confined to main. */
internal class CameraInputAuthority(private val owner: MapState) {
  private var commandRevision = 0L
  private var cameraGeneration = 0L
  private var inputGeneration = 0L
  private var active: CameraInputToken? = null
  private val programmaticJobs = mutableSetOf<Job>()
  private var configuration = CameraConfiguration()

  /** Callback replacements do not revoke input. Resolved policy and momentum changes do. */
  fun updateConfiguration(value: CameraConfiguration) {
    owner.lifecycle.requireMain()
    val changed = configuration.settings != value.settings
    configuration = value
    if (changed) {
      inputGeneration++
      revokeActive()?.cancelWork()
    }
  }

  val generation: Long
    get() = inputGeneration

  fun acquire(
    adapter: MapAdapter,
    expectedInputGeneration: Long? = null,
  ): CameraInputToken {
    owner.lifecycle.requireMain()
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
    if (!ready) return token
    val previous = revokeActive()
    val previousJobs = programmaticJobs.toList()
    programmaticJobs.clear()
    cameraGeneration++
    inputGeneration++
    active = token
    previousJobs.forEach { it.cancel(CancellationException("A newer input owns the camera")) }
    previous?.cancelWork()
    return token
  }

  /** A delayed click may acquire camera authority only if no newer accepted input intervened. */
  fun acquireIfCurrent(adapter: MapAdapter, generation: Long): CameraInputToken? =
    acquire(adapter, expectedInputGeneration = generation).takeIf { it.acceptsCommands }

  /** Even input with no camera response invalidates an older click's camera fallthrough. */
  fun observeInput(): Long = ++inputGeneration

  fun beginProgrammatic(
    job: Job? = null,
    concurrent: Boolean = false,
    supersededByAnyCommand: Boolean = false,
  ): CameraCommandGuard {
    owner.lifecycle.requireMain()
    job?.ensureActive()
    check(!owner.isClosed) { "The map state is closed" }
    val previous = revokeActive()
    val revision = ++commandRevision
    val previousJobs = if (concurrent) emptyList() else programmaticJobs.toList()
    if (!concurrent) {
      programmaticJobs.clear()
      cameraGeneration++
    }
    job?.let(programmaticJobs::add)
    inputGeneration++
    val generation = cameraGeneration
    previousJobs.forEach { it.cancel(CancellationException("A newer command owns the camera")) }
    job?.invokeOnCompletion {
      owner.lifecycle.postToMain { programmaticJobs.remove(job) }
    }
    previous?.cancelWork()
    return CameraCommandGuard {
      !owner.isClosed &&
        cameraGeneration == generation &&
        job?.isCancelled != true &&
        (!supersededByAnyCommand || commandRevision == revision)
    }
  }

  /** Revokes input when its presentation ends. */
  fun detach(attachment: MapAttachment) {
    owner.lifecycle.requireMain()
    inputGeneration++
    val token = active?.takeIf { it.attachment === attachment }
    token?.revoke()
    cameraGeneration++
    val jobs = programmaticJobs.toList()
    programmaticJobs.clear()
    owner.runtime.physicalScope.launch {
      jobs.forEach { it.cancel(MapAttachmentChangedException()) }
      token?.cancelWork()
    }
  }

  private fun revokeActive(): Token? = active?.also { it.revoke() }

  private enum class Status {
    Open,
    Cancelled,
    Completed,
  }

  /** Backends execute and finish this main-confined input lifetime. */
  inner class Token
  internal constructor(
    val attachment: MapAttachment?,
    private val target: CameraInputTarget?,
    ready: Boolean,
  ) {
    private var status = if (ready) Status.Open else Status.Cancelled
    private var job: Job? = null
    private val completion = CompletableDeferred<Unit>().also { if (!ready) it.complete(Unit) }
    private val startedComponents = mutableSetOf<CameraComponent>()
    private var rotated = false
    private var hapticDetector: BearingHapticDetector? = null
    private var onHaptic: ((HapticEmphasis) -> Unit)? = null
    private val hapticClock = TimeSource.Monotonic.markNow()

    fun setHapticFeedback(callback: (HapticEmphasis) -> Unit) {
      onHaptic = callback
    }

    /** Called after a direct rotation executes. */
    fun reportRotation(from: Double, to: Double) {
      if (!acceptsCommands) return
      val callback = onHaptic ?: return
      val detector =
        hapticDetector
          ?: BearingHapticDetector(configuration.settings.rotate.haptics).also {
            hapticDetector = it
          }
      detector.update(from, to, hapticClock.elapsedNow())?.let(callback)
    }

    val bearingSnapping: BearingSnapping?
      get() =
        configuration.settings.rotate.snapping.takeIf {
          acceptsCommands && rotated && it.enabled
        }

    val acceptsCommands: Boolean
      get() =
        active === this &&
          attachment?.let(owner.attachmentAuthority::isCurrent) == true &&
          target?.isGestureReady == true &&
          job?.isActive != false &&
          status == Status.Open

    fun permitted(component: CameraComponent): Boolean =
      acceptsCommands && configuration.settings.enabled(component)

    /** Application callbacks may replace this camera operation. */
    fun prepare(component: CameraComponent): Boolean {
      if (!permitted(component)) return false
      if (component == CameraComponent.Rotate) rotated = true
      if (startedComponents.add(component)) configuration.onStart[component]?.invoke()
      return permitted(component)
    }

    fun rearm(component: CameraComponent) {
      startedComponents.remove(component)
      if (component == CameraComponent.Rotate) hapticDetector = null
    }

    fun registerJob(value: Job) {
      job = value
      if (status == Status.Cancelled)
        value.cancel(CancellationException("A newer input owns the camera"))
    }

    fun finish(cancelled: Boolean, action: () -> Unit) {
      if (status == Status.Completed || completion.isCompleted) return
      if (cancelled) revoke()
      status = Status.Completed
      action()
    }

    fun complete() {
      if (status != Status.Cancelled) status = Status.Completed
      if (active === this) active = null
      completion.complete(Unit)
    }

    suspend fun awaitCompletion() = completion.await()

    fun revoke() {
      if (status != Status.Completed) status = Status.Cancelled
      if (active === this) active = null
    }

    fun cancelWork() {
      job?.cancel(CancellationException("A newer input owns the camera"))
      target?.cancelGesture(this)
    }
  }
}
