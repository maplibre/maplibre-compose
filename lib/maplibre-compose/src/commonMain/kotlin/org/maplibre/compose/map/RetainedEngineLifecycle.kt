@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** The physical steps of a session that keeps its engine between presentations. */
internal interface RetainedEngineSteps {
  suspend fun createEngine(identity: EngineMapIdentity)

  suspend fun attach(identity: EngineMapIdentity, lease: RenderLease)

  suspend fun detach(identity: EngineMapIdentity, lease: RenderLease)

  suspend fun destroyEngine(identity: EngineMapIdentity)

  suspend fun closeResources()
}

internal class MapLeaseInvalidatedException :
  CancellationException("The presentation lease ended before attachment completed")

internal class MapClosedException : IllegalStateException("The map is closed")

/**
 * Orders one retained-engine session's engine creation, attachment, detachment and closure.
 *
 * Requests are made on the main thread, except [close], which any thread may call. Each request
 * queues physical steps on [scope]. A step runs after every step queued before it, one at a time,
 * and keeps running if the caller that requested it is cancelled. A step that nothing precedes
 * starts inline, so an attachment requested in the apply phase begins before the frame ends.
 *
 * Main-thread fields record what was requested; step fields record what physically exists. Besides
 * [isClosing] and the queue, the two sides share only each engine record and presentation: steps
 * report on them through their volatile flags and deferreds, and main reads those.
 */
internal class RetainedEngineLifecycle(
  private val steps: RetainedEngineSteps,
  private val scope: CoroutineScope,
  private val mainThread: MainThreadGuard,
  private val onClosing: () -> Unit,
) {
  private val closing = AtomicBoolean(false)
  private val closure = CompletableDeferred<Result<Unit>>()
  private val queueTail = AtomicReference<Deferred<Unit>>(CompletableDeferred(Unit))

  // Main thread.
  private var lastIdentity = 0L
  private var requestedEngine: EngineRecord? = null
  private var presentation: Presentation? = null
  private var lastDetach: Deferred<Result<Unit>>? = null

  // Steps only; the queue orders every access.
  private var createdEngine: EngineMapIdentity? = null
  private var partialEngine: EngineMapIdentity? = null
  private var attachedPresentation: Presentation? = null
  private val lateDetachFailures = mutableListOf<Throwable>()

  /** True once [close] has been requested. Readable from any thread. */
  val isClosing: Boolean
    get() = closing.load()

  /** Main thread. The engine that exists or is being created, or null. */
  val engine: EngineMapIdentity?
    get() = liveEngine()?.identity

  /** Main thread. The presentation whose attachment completed and has not ended, or null. */
  val lease: RenderLease?
    get() = presentation?.takeIf { it.attached && !isClosing }?.lease

  /**
   * Main thread. Requests an attachment unless one is current, creating the engine first if none
   * exists. Returns false once closing. The attachment runs after any detachment still in progress.
   */
  fun beginAttach(): Boolean {
    mainThread.requireMain()
    if (isClosing) return false
    forgetFailedAttach()
    if (presentation != null) return true
    val live = liveEngine()
    val record =
      live ?: EngineRecord(EngineMapIdentity(++lastIdentity)).also { requestedEngine = it }
    val next =
      Presentation(
        record,
        RenderLease(++lastIdentity),
        createsEngine = live == null,
        precedingDetach = lastDetach?.takeUnless { it.isCompleted },
      )
    presentation = next
    next.attachment = enqueue { attachStep(next) }
    return true
  }

  /**
   * Main thread. Attaches as [beginAttach] does and waits for the attachment. Throws
   * [MapLeaseInvalidatedException] if the presentation ends first, and the failure of a detachment
   * that the attachment waited for. If the caller is cancelled after this call began the
   * attachment, the presentation ends; its physical cleanup continues.
   */
  suspend fun attach() {
    mainThread.requireMain()
    if (isClosing) throw MapClosedException()
    forgetFailedAttach()
    val began = presentation == null
    // Another thread can close the session after the check above.
    if (began && !beginAttach()) throw MapClosedException()
    val current = checkNotNull(presentation)
    val result =
      try {
        current.attachment.await()
      } catch (cancelled: CancellationException) {
        if (began && presentation === current) endPresentation(current)
        throw cancelled
      }
    if (isClosing || presentation !== current) {
      throw MapLeaseInvalidatedException().also { invalidated ->
        result.exceptionOrNull()?.let(invalidated::addSuppressed)
      }
    }
    result.getOrThrow()
  }

  /**
   * Main thread. Ends the current presentation and waits for its physical detachment, or joins a
   * detachment already in progress. Returns whether a presentation was current. Throws the
   * detachment's cleanup failures.
   */
  suspend fun detach(): Boolean {
    mainThread.requireMain()
    if (isClosing) return false
    forgetFailedAttach()
    val current = presentation
    if (current == null) {
      lastDetach?.takeUnless { it.isCompleted }?.await()?.getOrThrow()
      return false
    }
    endPresentation(current).await().getOrThrow()
    return true
  }

  /**
   * Main thread. Returns the engine, creating it without a presentation if none exists. Waits for
   * an attachment or detachment in progress and throws its failure; a presentation that ends does
   * not fail engine access. Throws [CancellationException] once closing.
   */
  suspend fun ensureEngine(): EngineMapIdentity {
    mainThread.requireMain()
    while (true) {
      if (isClosing) throw CancellationException(CLOSED_BEFORE_ACCESS)
      forgetFailedAttach()
      val attaching = presentation?.takeUnless { it.attached }
      if (attaching != null) {
        val failure = attaching.attachment.await().exceptionOrNull()
        val invalidated = failure is MapLeaseInvalidatedException || presentation !== attaching
        if (failure != null && !invalidated) throw engineAccessFailure(failure)
        continue
      }
      val detaching = lastDetach?.takeUnless { it.isCompleted }
      if (detaching != null) {
        detaching.await().exceptionOrNull()?.let { throw engineAccessFailure(it) }
        continue
      }
      val record =
        liveEngine()
          ?: EngineRecord(EngineMapIdentity(++lastIdentity)).also { created ->
            requestedEngine = created
            enqueue { engineStep(created) }
          }
      val failure = record.created.await().exceptionOrNull()
      // An attachment that would have created the engine ended before it started.
      if (failure is MapLeaseInvalidatedException && !isClosing) continue
      if (failure != null) throw engineAccessFailure(failure)
      if (isClosing) throw CancellationException(CLOSED_BEFORE_ACCESS)
      return record.identity
    }
  }

  /**
   * Any thread. Rejects new requests at once and reports [onClosing]. Cleanup runs after every step
   * already queued: it detaches a current presentation, destroys the engine, and releases the
   * session's resources.
   */
  fun close() {
    if (!closing.compareAndSet(expectedValue = false, newValue = true)) return
    val notice = runCatching(onClosing).exceptionOrNull()
    enqueue { closeStep(notice) }
  }

  /** Waits for [close]'s cleanup and throws every failure it collected. */
  suspend fun awaitClosed() {
    closure.await().getOrThrow()
  }

  private fun liveEngine(): EngineRecord? = requestedEngine?.takeUnless { it.failed }

  private fun engineAccessFailure(failure: Throwable): Throwable =
    if (isClosing && failure !is CancellationException && failure !is Error) {
      CancellationException(CLOSED_BEFORE_ACCESS, failure)
    } else {
      failure
    }

  /** An attachment that failed while current cleaned up after itself and leaves nothing current. */
  private fun forgetFailedAttach() {
    if (presentation?.failed == true) presentation = null
  }

  private fun endPresentation(ended: Presentation): Deferred<Result<Unit>> {
    // Stamps with this lease are refused from here on, before any physical work.
    ended.ended = true
    presentation = null
    // An attachment that has not started never will. It creates no engine, so later requests do
    // not wait for the one it was to create.
    if (ended.claimStep() && ended.createsEngine) {
      ended.engine.complete(MapLeaseInvalidatedException())
    }
    return enqueue { detachStep(ended) }.also { lastDetach = it }
  }

  private fun <T> enqueue(step: suspend () -> T): Deferred<Result<T>> {
    val done = CompletableDeferred<Unit>()
    val previous = queueTail.exchange(done)
    return scope.async(start = CoroutineStart.UNDISPATCHED) {
      try {
        previous.await()
        runCatching { step() }
      } finally {
        done.complete(Unit)
      }
    }
  }

  private suspend fun engineStep(record: EngineRecord) {
    // Closure can be requested after ensureEngine checked it, and its cleanup can run before this.
    if (isClosing) {
      record.complete(CancellationException(CLOSED_BEFORE_ACCESS))
      return
    }
    val identity = record.identity
    try {
      steps.createEngine(identity)
      createdEngine = identity
      record.complete(null)
    } catch (error: Throwable) {
      runCatching { steps.destroyEngine(identity) }.exceptionOrNull()?.let(error::addSuppressed)
      record.complete(error)
      throw error
    }
  }

  private suspend fun attachStep(attaching: Presentation) {
    val record = attaching.engine
    val detachFailure = attaching.precedingDetach?.await()?.exceptionOrNull()
    // Queued behind other steps and no longer wanted: nothing physical has started. A presentation
    // that ended first has already given up its engine record.
    if (!attaching.claimStep()) throw MapLeaseInvalidatedException()
    if (isClosing) {
      val invalidated = MapLeaseInvalidatedException()
      if (attaching.createsEngine) record.complete(invalidated)
      throw invalidated
    }
    // A failed detachment leaves the engine detached, and the attachment that waited for it fails
    // with it.
    if (detachFailure != null) {
      if (attaching.createsEngine) record.complete(detachFailure)
      attaching.failed = true
      throw detachFailure
    }
    val identity = record.identity
    var attachAttempted = false
    try {
      if (attaching.createsEngine) {
        attachedPresentation = attaching
        partialEngine = identity
        try {
          steps.createEngine(identity)
        } catch (error: Throwable) {
          record.complete(error)
          throw error
        }
        partialEngine = null
        createdEngine = identity
        record.complete(null)
      } else {
        // An engine that another step failed to create leaves nothing to detach.
        record.created.await().getOrThrow()
        attachedPresentation = attaching
      }
      attachAttempted = true
      steps.attach(identity, attaching.lease)
      attaching.attached = true
    } catch (error: Throwable) {
      // A lease that ended or a closure leaves this cleanup to the detach or close step queued
      // behind this one. Otherwise nothing follows, so the attempt cleans up here.
      if (!attaching.ended && !isClosing) {
        attachedPresentation = null
        if (attachAttempted) {
          runCatching { steps.detach(identity, attaching.lease) }
            .exceptionOrNull()
            ?.let(error::addSuppressed)
        }
        partialEngine?.let { partial ->
          partialEngine = null
          runCatching { steps.destroyEngine(partial) }.exceptionOrNull()?.let(error::addSuppressed)
        }
        attaching.failed = true
      }
      throw error
    }
  }

  private suspend fun detachStep(ended: Presentation) {
    // The attachment never started, or it failed while current and cleaned up after itself.
    if (attachedPresentation !== ended) return
    attachedPresentation = null
    val identity = ended.engine.identity
    val failures = mutableListOf<Throwable>()
    collectFailure(failures) { steps.detach(identity, ended.lease) }
    if (partialEngine == identity) {
      partialEngine = null
      collectFailure(failures) { steps.destroyEngine(identity) }
    }
    failures.cleanupResult("Map").onFailure { failure ->
      // A closure queued behind this detachment reports its failure.
      if (isClosing) lateDetachFailures += failure
      throw failure
    }
  }

  private suspend fun closeStep(notice: Throwable?) {
    val failures = mutableListOf<Throwable>()
    notice?.let(failures::add)
    lateDetachFailures.forEach(failures::addCleanupFailure)
    attachedPresentation?.let { attached ->
      attachedPresentation = null
      collectFailure(failures) { steps.detach(attached.engine.identity, attached.lease) }
    }
    (createdEngine ?: partialEngine)?.let { identity ->
      createdEngine = null
      partialEngine = null
      collectFailure(failures) { steps.destroyEngine(identity) }
    }
    collectFailure(failures) { steps.closeResources() }
    closure.complete(failures.cleanupResult("Map"))
  }

  private suspend fun collectFailure(
    failures: MutableList<Throwable>,
    cleanup: suspend () -> Unit,
  ) {
    runCatching { cleanup() }.exceptionOrNull()?.let(failures::add)
  }

  private class EngineRecord(val identity: EngineMapIdentity) {
    /**
     * Completed by the step that creates this engine, with that step's failure if it failed. Main
     * completes it instead when the presentation that was to create it ends before its step starts.
     */
    val created = CompletableDeferred<Result<Unit>>()

    @Volatile
    var failed = false
      private set

    fun complete(failure: Throwable?) {
      failed = failure != null
      created.complete(failure?.let { Result.failure(it) } ?: Result.success(Unit))
    }
  }

  private class Presentation(
    val engine: EngineRecord,
    val lease: RenderLease,
    val createsEngine: Boolean,
    /** The detachment in progress when this presentation began. Its attachment runs after it. */
    val precedingDetach: Deferred<Result<Unit>>?,
  ) {
    lateinit var attachment: Deferred<Result<Unit>>

    private val stepClaimed = AtomicBoolean(false)

    /**
     * Returns true for the first caller only. The attach step claims itself when it starts; main
     * claims it when the presentation ends first, and the step then does nothing.
     */
    fun claimStep(): Boolean = stepClaimed.compareAndSet(expectedValue = false, newValue = true)

    /** Set on main when the presentation ends. A later step then owns its cleanup. */
    @Volatile var ended = false

    /** Set by the attach step once the engine is attached. */
    @Volatile var attached = false

    /** Set by the attach step when it failed while current and cleaned up after itself. */
    @Volatile var failed = false
  }

  private companion object {
    const val CLOSED_BEFORE_ACCESS = "The map closed before engine access could begin"
  }
}
