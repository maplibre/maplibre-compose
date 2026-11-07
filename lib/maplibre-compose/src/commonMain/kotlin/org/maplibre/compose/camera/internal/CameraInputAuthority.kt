package org.maplibre.compose.camera.internal

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.time.TimeSource
import kotlin.time.TimeSource.Monotonic.ValueTimeMark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maplibre.compose.interaction.internal.BearingHapticDetector
import org.maplibre.compose.interaction.internal.CameraComponent
import org.maplibre.compose.interaction.internal.CameraConfiguration
import org.maplibre.compose.interaction.internal.GestureMomentum
import org.maplibre.compose.interaction.internal.GlideVelocity
import org.maplibre.compose.interaction.internal.ReleaseVelocity
import org.maplibre.compose.interaction.internal.resolveGestureMomentum
import org.maplibre.compose.map.MapAdapter
import org.maplibre.compose.map.MapAttachment
import org.maplibre.compose.map.MapAttachmentChangedException
import org.maplibre.compose.map.MapState
import org.maplibre.compose.style.systemAnimatorDurationScale

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
  private var cancelledGlide: Pair<GlideVelocity, ValueTimeMark>? = null

  /**
   * Records how fast a gesture's momentum still moved when something stopped it. The next input to
   * release takes it if that input started before the glide stopped, so a flick that stops a glide
   * carries it on.
   */
  fun glideCancelled(velocity: GlideVelocity) {
    cancelledGlide = velocity to TimeSource.Monotonic.markNow()
  }

  private fun takeCancelledGlide(startedAt: ValueTimeMark): GlideVelocity? {
    val (velocity, cancelledAt) = cancelledGlide ?: return null
    cancelledGlide = null
    return velocity.takeIf { cancelledAt >= startedAt }
  }

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
    private val changedComponents = mutableSetOf<CameraComponent>()
    private var hapticDetector: BearingHapticDetector? = null
    private var onHaptic: ((HapticFeedbackType) -> Unit)? = null
    private val startedAt = TimeSource.Monotonic.markNow()

    fun setHapticFeedback(callback: (HapticFeedbackType) -> Unit) {
      onHaptic = callback
    }

    /** Called after a rotation executes, including one by this input's momentum. */
    fun reportRotation(from: Double, to: Double) {
      if (!acceptsFeedback) return
      val callback = onHaptic ?: return
      val detector =
        hapticDetector
          ?: BearingHapticDetector(configuration.settings.rotate.haptics).also {
            hapticDetector = it
          }
      detector.update(from, to, startedAt.elapsedNow())?.let(callback)
    }

    /**
     * True from acceptance until completion, including the momentum that continues the input after
     * it ends, unless newer input or a camera command took over.
     */
    val acceptsFeedback: Boolean
      get() =
        active === this &&
          attachment?.let(owner.attachmentAuthority::isCurrent) == true &&
          !completion.isCompleted &&
          status != Status.Cancelled

    /**
     * The momentum that [release] starts, with the snaps of the parts this input changed. Starts
     * the parts that will move. Null when nothing moves or settles.
     */
    fun momentum(release: ReleaseVelocity?): GestureMomentum? {
      if (!acceptsCommands) return null
      return resolveGestureMomentum(
        configuration.settings,
        release,
        takeCancelledGlide(startedAt),
        changedComponents,
        ::prepare,
        systemAnimatorDurationScale(),
      )
    }

    /** Records how fast this input's momentum still moved when something stopped it. */
    fun glideCancelled(velocity: GlideVelocity) {
      if (attachment?.let(owner.attachmentAuthority::isCurrent) == true)
        this@CameraInputAuthority.glideCancelled(velocity)
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
      changedComponents += component
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
