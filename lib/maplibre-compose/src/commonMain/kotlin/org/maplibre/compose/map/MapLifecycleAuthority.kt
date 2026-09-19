@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmInline
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.internal.CameraInputAuthority

/** Identifies one platform engine-map instance until its destruction. */
@JvmInline internal value class EngineMapIdentity(private val value: Long)

/** Identifies one temporary attachment of a logical map to a presentation. */
@JvmInline internal value class RenderLease(private val value: Long)

/** Identifies one loaded base-style generation on one engine map. */
@JvmInline internal value class StyleIdentity(private val value: Long)

/** Identifies one requested base-style generation before it has loaded. */
@JvmInline internal value class StyleRequestIdentity(private val value: Long)

/** Specifies whether presentation detachment destroys its engine map. */
internal enum class EngineRetention {
  RETAIN,
  DESTROY,
}

/** Defines platform commands for the logical-map lifecycle authority. */
internal interface MapLifecyclePlatformAdapter {
  val engineRetention: EngineRetention

  suspend fun createEngine(identity: EngineMapIdentity)

  suspend fun attach(identity: EngineMapIdentity, lease: RenderLease)

  suspend fun detach(identity: EngineMapIdentity, lease: RenderLease)

  suspend fun destroyEngine(identity: EngineMapIdentity)

  suspend fun closeResources()
}

/** Defines a platform map session with a physical lifecycle controlled by [MapState]. */
internal interface MapLifecycleSession : MapAdapter, MapLifecyclePlatformAdapter

internal class MapAlreadyAttachedException :
  IllegalStateException("The map already has a presentation")

internal class MapLeaseInvalidatedException :
  CancellationException("The presentation lease ended before attachment completed")

internal class MapClosedException : IllegalStateException("The map is closed")

internal data class PendingAttachment(
  val lease: RenderLease,
  val completion: CompletableDeferred<Result<RenderLease>>,
)

/**
 * Owns every logical and physical lifecycle transition for one [MapState].
 *
 * Decisions run on the main thread: presentation reservation, publication, release, closure, and
 * platform bindings. Physical work runs on [physicalScope] and returns to main to commit. Engine
 * threads read a [snapshot] instead of taking a lock, and [close] may be called from any thread
 * because it only posts.
 */
internal class MapLifecycleAuthority(
  private val owner: MapState,
  private val physicalScope: CoroutineScope,
  private val mainDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
  private val mainThread: MainThreadGuard = MainThreadGuard(mainDispatcher),
) {
  internal val gestureCamera: CameraInputAuthority
    get() = owner.gestureAuthority

  /** Fails unless the caller is on the main thread. Map-state mutators call this first. */
  fun requireMain() = mainThread.requireMain()

  /**
   * Runs [block] on the main dispatcher, inline when the caller is already there. Inline posts can
   * run ahead of posts that other threads queued earlier, so a posted block must not assume the
   * state it saw when it was queued.
   */
  fun postToMain(block: () -> Unit) = postToMain(mainDispatcher, mainThread, block)

  private val platforms = mutableMapOf<MapLifecycleSession, MapLifecycleBinding>()
  private val closure = CompletableDeferred<Result<Unit>>()
  private var attachment: Attachment? = null
  private var retainedAdapter: MapAdapter? = null
  private val retiringAdapters = linkedSetOf<MapAdapter>()
  private val releaseCleanups = linkedSetOf<CompletableDeferred<Result<Unit>>>()
  private val pendingCleanupFailures = mutableListOf<Throwable>()
  private var closed = false

  /** Set by [close] on any thread before the closure commits on main. */
  private val closeRequested = AtomicBoolean(false)

  /**
   * What engine threads may read: the current presentation and closure. Rebuilt on main after every
   * change, so a reader sees one consistent state.
   */
  @Volatile private var snapshot = Snapshot()

  /**
   * Platform callbacks run on engine threads and closure runs on main. The one thing they must
   * agree on is that closure waits for a callback in progress, which this lock provides.
   */
  private val platformAccessLock = reentrantLock()
  private var platformAccessDepth = 0
  private var closeAfterPlatformAccess = false

  /** True once [close] has been requested, readable from any thread. */
  val isClosed: Boolean
    get() = closeRequested.load()

  /**
   * Closes from any thread. The closure commits on main, and [awaitClosed] waits for that commit
   * and every cleanup it starts.
   */
  fun close() {
    if (!closeRequested.compareAndSet(expectedValue = false, newValue = true)) return
    postToMain { closeOnMain() }
  }

  private fun closeOnMain() {
    requireMain()
    val deferred = platformAccessLock.withLock {
      if (platformAccessDepth > 0) {
        closeAfterPlatformAccess = true
        true
      } else {
        closed = true
        false
      }
    }
    if (deferred) return
    val maps = buildSet {
      retainedAdapter?.let(::add)
      attachment?.adapter?.let(::add)
      addAll(retiringAdapters)
      addAll(platforms.keys)
    }
    val recordedFailures = pendingCleanupFailures.toList()
    val releases = releaseCleanups.toList()
    attachment = null
    retainedAdapter = null
    retiringAdapters.clear()
    pendingCleanupFailures.clear()
    publishSnapshot()
    owner.attachmentAuthority.commitClosed()
    maps.forEach(MapAdapter::close)
    physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
      val failures = mutableListOf<Throwable>()
      recordedFailures.forEach { addCleanupFailure(failures, it) }
      releases.forEach { release ->
        release.await().exceptionOrNull()?.let { addCleanupFailure(failures, it) }
      }
      maps.forEach { map ->
        runCatching { map.awaitClosed() }.exceptionOrNull()?.let { addCleanupFailure(failures, it) }
      }
      completeClosure(failures.cleanupResult("Map state"))
    }
  }

  suspend fun awaitClosed() {
    closure.await().getOrThrow()
  }

  fun reservePresentation(ownerToken: MapPresentationOwnerToken): MapPresentationToken {
    requireMain()
    requireOpen()
    val current = attachment
    check(current == null || current.releasing || current.owner === ownerToken) {
      "The map state already has a presentation"
    }
    val replaced = current?.adapter?.takeUnless { current.releasing }
    if (replaced != null) owner.attachmentAuthority.invalidatePresentation(replaced)
    val token = MapPresentationToken(nextPresentationToken.incrementAndFetch())
    attachment = Attachment(ownerToken, token)
    publishSnapshot()
    replaced?.let { adapter ->
      physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
        runCatching { adapter.detachPresentation() }
      }
    }
    return token
  }

  fun publishPresentation(token: MapPresentationToken, adapter: MapAdapter) {
    requireMain()
    if (closed) return
    val current = attachment
    check(current?.token == token && !current.releasing) {
      "The map presentation reservation is no longer current"
    }
    if (current.adapter === adapter && owner.currentMapAttachment?.adapter === adapter) return
    selectAdapter(current, adapter)
    val retainedToReplace = retainedAdapter?.takeUnless { retained ->
      retained === adapter || !adapter.retainsEngineBetweenPresentations
    }
    try {
      owner.attachmentAuthority.configurePresentationAdapter(adapter)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      owner.styleAuthority.markStyleFailed(adapter, error.message)
    }
    // Configuration can re-enter and replace this reservation.
    if (closed || attachment !== current || current.releasing || current.adapter !== adapter) return
    if (adapter.retainsEngineBetweenPresentations) retainedAdapter = adapter
    retainedToReplace?.let(retiringAdapters::add)
    owner.attachmentAuthority.commitPresentation(token = token, adapter = adapter)
    publishSnapshot()
    owner.attachmentAuthority.seedPresentationViewport(token, adapter)
    if (retainedToReplace != null) {
      retainedToReplace.close()
      physicalScope.launch {
        val failure = runCatching { retainedToReplace.awaitClosed() }.exceptionOrNull()
        withContext(mainDispatcher) {
          retiringAdapters.remove(retainedToReplace)
          if (failure != null && !closed) pendingCleanupFailures += failure
        }
      }
    }
  }

  fun releasePresentation(token: MapPresentationToken, adapter: MapAdapter?) {
    requireMain()
    val current = attachment ?: return
    if (current.token != token) return
    if (adapter != null && current.adapter !== adapter) return
    if (current.releasing) return
    current.releasing = true
    publishSnapshot()
    owner.attachmentAuthority.invalidatePresentation(current.adapter)
    val closingAdapter = current.adapter
    if (closingAdapter == null) {
      if (attachment === current) attachment = null
      publishSnapshot()
      return
    }
    val completion = CompletableDeferred<Result<Unit>>().also(releaseCleanups::add)
    physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
      val result = runCatching { closingAdapter.detachPresentation() }
      withContext(mainDispatcher) {
        releaseCleanups.remove(completion)
        if (!closed) result.exceptionOrNull()?.let(pendingCleanupFailures::add)
        if (attachment === current) attachment = null
        publishSnapshot()
      }
      completion.complete(result)
    }
  }

  fun retainedAdapter(compatibilityKey: Any): MapAdapter? {
    requireMain()
    return retainedAdapter?.takeIf { adapter ->
      adapter.retainsEngineBetweenPresentations &&
        adapter.presentationCompatibilityKey == compatibilityKey
    }
  }

  /**
   * Seeds the published presentation from [adapter] after that adapter first has a viewport. Called
   * from the engine thread.
   *
   * Publication also seeds, but the first frame snapshot can arrive while the lease is still
   * attaching. The attach-gated camera callback is then rejected, and an idle empty style may never
   * emit another one.
   */
  fun seedCurrentPresentationViewport(adapter: MapAdapter) = postToMain {
    val token =
      attachment?.takeIf { it.adapter === adapter && !it.releasing }?.token ?: return@postToMain
    owner.attachmentAuthority.seedPresentationViewport(token, adapter)
  }

  /**
   * Ends a camera change that the engine behind [adapter] started and will never finish, such as
   * one interrupted by the loss of the rendering context. Called from the engine thread.
   */
  fun endCurrentPresentationCameraChange(adapter: MapAdapter) = postToMain {
    owner.attachmentAuthority.endCameraChange(adapter)
  }

  fun selectAdapterForPresentation(adapter: MapAdapter): Boolean {
    requireMain()
    if (closed) return false
    val current = attachment ?: return false
    if (current.releasing) return false
    selectAdapter(current, adapter)
    return true
  }

  /** Whether [adapter] is the presentation or retained engine. Readable from any thread. */
  fun acceptsAdapter(adapter: MapAdapter): Boolean = snapshot.acceptsAdapter(adapter)

  fun isPendingPublication(adapter: MapAdapter): Boolean {
    requireMain()
    return !closed && attachment?.adapter === adapter && attachment?.releasing == false
  }

  /** Whether [adapter] is the published presentation. Readable from any thread. */
  fun acceptsPresentation(adapter: MapAdapter): Boolean =
    snapshot.acceptsPresentation(adapter) && owner.currentMapAttachment?.adapter === adapter

  fun currentAdapter(): MapAdapter? {
    requireMain()
    return attachment?.adapter ?: retainedAdapter
  }

  fun retainAdapterForPlatformAccess(create: () -> MapAdapter): MapAdapter {
    requireMain()
    requireOpen()
    (attachment?.adapter ?: retainedAdapter)?.let {
      return it
    }
    return create().also { adapter ->
      check(adapter.retainsEngineBetweenPresentations) {
        "A detached platform map requires an engine-retaining adapter"
      }
      retainedAdapter = adapter
      publishSnapshot()
      owner.styleAuthority.beginStyleLoadForNewAdapter()
    }
  }

  fun presentationAdapterForPlatformAccess(): MapAdapter {
    requireMain()
    requireOpen()
    val adapter = attachment?.adapter
    check(adapter != null && acceptsPresentation(adapter)) {
      "Platform map access requires an attached Web map surface"
    }
    return adapter
  }

  /** Runs a platform callback on the calling engine thread; closure waits for it. */
  fun acceptEnginePlatformAccess(adapter: MapAdapter, event: () -> Unit): Boolean =
    acceptPlatformAccess(accepts = { snapshot.acceptsAdapter(adapter) }, event)

  fun acceptPresentationPlatformAccess(adapter: MapAdapter, event: () -> Unit): Boolean =
    acceptPlatformAccess(accepts = { acceptsPresentation(adapter) }, event)

  /** Whether [token] and [adapter] are the published presentation. Readable from any thread. */
  fun isCurrent(token: MapPresentationToken, adapter: MapAdapter): Boolean {
    val seen = snapshot
    return !seen.closed &&
      seen.token == token &&
      seen.adapter === adapter &&
      owner.currentMapAttachment?.let { it.token == token && it.adapter === adapter } == true
  }

  fun bind(adapter: MapLifecyclePlatformAdapter): MapLifecycleBinding {
    requireMain()
    val session = adapter as? MapLifecycleSession
    session?.let(platforms::get)?.let {
      return it
    }
    val lifecycle =
      MapLifecycleBinding(adapter, physicalScope, mainDispatcher, mainThread) { binding ->
        if (session != null) postToMain { retireClosingSession(session, binding) }
      }
    if (closed) lifecycle.close() else if (session != null) platforms[session] = lifecycle
    if (session != null) {
      physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
        val failure = runCatching { lifecycle.awaitClosed() }.exceptionOrNull()
        withContext(mainDispatcher) {
          if (platforms[session] === lifecycle) platforms.remove(session)
          retiringAdapters.remove(session)
          if (failure != null && !closed) pendingCleanupFailures += failure
        }
      }
    }
    return lifecycle
  }

  private fun retireClosingSession(session: MapLifecycleSession, binding: MapLifecycleBinding) {
    if (platforms[session] === binding) platforms.remove(session)
    val wasAttached = attachment?.adapter === session
    val wasRetained = retainedAdapter === session
    if (wasAttached) attachment = null
    if (wasRetained) retainedAdapter = null
    retiringAdapters += session
    publishSnapshot()
    if (wasAttached || wasRetained) owner.attachmentAuthority.invalidateClosedAdapter(session)
  }

  private fun requireOpen() {
    check(!closed) { "The map state is closed" }
  }

  private fun selectAdapter(current: Attachment, adapter: MapAdapter) {
    if (current.adapter === adapter) return
    check(current.adapter == null) { "The map state already has a presentation adapter" }
    current.adapter = adapter
    publishSnapshot()
    if (retainedAdapter !== adapter) owner.styleAuthority.beginStyleLoadForNewAdapter()
  }

  private fun publishSnapshot() {
    val current = attachment
    snapshot =
      Snapshot(
        closed = closed,
        token = current?.token,
        adapter = current?.adapter,
        releasing = current?.releasing == true,
        retainedAdapter = retainedAdapter,
      )
  }

  private fun acceptPlatformAccess(accepts: () -> Boolean, event: () -> Unit): Boolean {
    var commitDeferredClose = false
    try {
      platformAccessLock.withLock {
        if (!accepts()) return false
        platformAccessDepth++
      }
      try {
        event()
      } finally {
        platformAccessLock.withLock {
          platformAccessDepth--
          if (platformAccessDepth == 0 && closeAfterPlatformAccess) {
            closeAfterPlatformAccess = false
            commitDeferredClose = true
          }
        }
      }
      return true
    } finally {
      if (commitDeferredClose) postToMain { closeOnMain() }
    }
  }

  private fun completeClosure(result: Result<Unit>) {
    if (closure.complete(result)) owner.runtime.childClosed(owner)
  }

  private fun addCleanupFailure(failures: MutableList<Throwable>, failure: Throwable) {
    failures.addCleanupFailure(failure)
  }

  private class Attachment(
    val owner: MapPresentationOwnerToken,
    val token: MapPresentationToken,
    var adapter: MapAdapter? = null,
    var releasing: Boolean = false,
  )

  /** An immutable view for engine threads. */
  private class Snapshot(
    val closed: Boolean = false,
    val token: MapPresentationToken? = null,
    val adapter: MapAdapter? = null,
    val releasing: Boolean = false,
    val retainedAdapter: MapAdapter? = null,
  ) {
    fun acceptsAdapter(candidate: MapAdapter): Boolean =
      !closed && ((adapter === candidate && !releasing) || retainedAdapter === candidate)

    fun acceptsPresentation(candidate: MapAdapter): Boolean = !closed && adapter === candidate
  }

  private companion object {
    val nextPresentationToken = AtomicLong(0L)
  }
}

/** Applies engine and render-lease transitions through platform commands. */
internal class MapLifecycleBinding(
  private val adapter: MapLifecyclePlatformAdapter,
  private val physicalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  private val mainDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
  private val mainThread: MainThreadGuard = MainThreadGuard(mainDispatcher),
  private val onClosing: (MapLifecycleBinding) -> Unit = {},
) {
  /** See [MapLifecycleAuthority.postToMain]. */
  fun postToMain(block: () -> Unit) = postToMain(mainDispatcher, mainThread, block)

  private val nextIdentity = AtomicLong(0L)
  private val current = AtomicReference<InternalState>(InternalState.OpenDetached(null))
  private val currentStyle = AtomicReference<StyleClaim?>(null)
  private val currentStyleRequest = AtomicReference<StyleRequestClaim?>(null)
  private val closure = CompletableDeferred<Result<Unit>>()

  val engineIdentity: EngineMapIdentity?
    get() = current.load().engine

  val renderLease: RenderLease?
    get() = (current.load() as? InternalState.Attached)?.lease

  fun claimStyleRequestIdentity(engine: EngineMapIdentity): StyleRequestIdentity? {
    if (!acceptEngineIdentity(engine)) return null
    val identity = StyleRequestIdentity(nextIdentity.incrementAndFetch())
    currentStyle.store(null)
    currentStyleRequest.store(StyleRequestClaim(engine, identity))
    return identity
  }

  fun acceptStyleRequestEvent(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
    event: () -> Unit,
  ): Boolean {
    if (!acceptEngineIdentity(engine)) return false
    if (currentStyleRequest.load() != StyleRequestClaim(engine, request)) return false
    event()
    return true
  }

  val acceptsWork: Boolean
    get() {
      val observed = current.load()
      return observed !is InternalState.Closing && observed !== InternalState.Closed
    }

  /** Claims a loaded style and delivers it; the claim is visible before [event] runs. */
  fun claimStyleIdentity(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
    event: (StyleIdentity) -> Unit,
  ): StyleIdentity? {
    if (!acceptEngineIdentity(engine)) return null
    if (currentStyleRequest.load() != StyleRequestClaim(engine, request)) return null
    val identity = StyleIdentity(nextIdentity.incrementAndFetch())
    currentStyle.store(StyleClaim(engine, identity))
    event(identity)
    return identity
  }

  fun invalidateStyleIdentity(engine: EngineMapIdentity): Boolean {
    if (!acceptEngineIdentity(engine)) return false
    val claimed = currentStyle.load() ?: return true
    if (claimed.engine != engine) return false
    currentStyle.store(null)
    return true
  }

  /** Accepts an engine-durable event, including while a retained native engine is detached. */
  fun acceptEngineEvent(engine: EngineMapIdentity, event: () -> Unit): Boolean {
    if (!acceptEngineIdentity(engine)) return false
    event()
    return true
  }

  /** Accepts an event only from the current loaded style on the current engine map. */
  fun acceptStyleEvent(
    engine: EngineMapIdentity,
    style: StyleIdentity,
    event: () -> Unit,
  ): Boolean {
    if (!acceptEngineIdentity(engine)) return false
    if (currentStyle.load() != StyleClaim(engine, style)) return false
    event()
    return true
  }

  private fun acceptEngineIdentity(engine: EngineMapIdentity): Boolean {
    val observed = current.load()
    if (observed is InternalState.Closing || observed === InternalState.Closed) return false
    return observed.engine == engine
  }

  suspend fun attach(): RenderLease {
    val request = beginAttach()
    try {
      return request.completion.await().getOrThrow()
    } catch (cancelled: CancellationException) {
      beginDetach(request.lease)
      throw cancelled
    }
  }

  /** Attaches after a preceding presentation has completed its physical detachment. */
  suspend fun attachRetainedEngine(): RenderLease {
    while (true) {
      when (val observed = current.load()) {
        is InternalState.OpenDetached -> return attach()
        is InternalState.CreatingEngine -> observed.result.await().getOrThrow()
        is InternalState.Attaching -> return observed.result.await().getOrThrow()
        is InternalState.Attached -> return observed.lease
        is InternalState.Detaching -> observed.result.await().getOrThrow()
        is InternalState.Closing,
        InternalState.Closed -> throw MapClosedException()
      }
    }
  }

  /** Commits attachment before starting its physical commands. */
  fun beginAttach(): PendingAttachment {
    while (true) {
      val observed = current.load()
      val start =
        when (observed) {
          is InternalState.OpenDetached -> createAttachmentStart(observed)
          is InternalState.CreatingEngine,
          is InternalState.Attaching,
          is InternalState.Attached,
          is InternalState.Detaching -> throw MapAlreadyAttachedException()
          is InternalState.Closing,
          InternalState.Closed -> throw MapClosedException()
        }
      if (!current.compareAndSet(observed, start.state)) continue
      launchAttachment(start)
      return start.pending
    }
  }

  /** Starts attachment atomically, or returns false after detachment or closure starts. */
  fun beginAttachIfOpen(): Boolean {
    while (true) {
      val observed = current.load()
      val start =
        when (observed) {
          is InternalState.OpenDetached -> createAttachmentStart(observed)
          is InternalState.Attaching,
          is InternalState.Attached,
          is InternalState.CreatingEngine -> return true
          is InternalState.Detaching,
          is InternalState.Closing,
          InternalState.Closed -> return false
        }
      if (!current.compareAndSet(observed, start.state)) continue
      launchAttachment(start)
      return true
    }
  }

  /** Creates a retained engine without requiring a presentation and returns its identity. */
  suspend fun ensureEngine(): EngineMapIdentity {
    check(adapter.engineRetention == EngineRetention.RETAIN) {
      "Detached engine creation requires a retained engine"
    }
    while (true) {
      var launchCreation = false
      val state = current.load()
      val observed =
        when (state) {
          is InternalState.OpenDetached -> {
            state.engine?.let {
              return it
            }
            val creating =
              InternalState.CreatingEngine(
                engine = EngineMapIdentity(nextIdentity.incrementAndFetch()),
                result = CompletableDeferred(),
                engineCreated = AtomicBoolean(false),
              )
            if (!current.compareAndSet(state, creating)) continue
            launchCreation = true
            creating
          }
          else -> state
        }
      when (observed) {
        is InternalState.CreatingEngine -> {
          if (launchCreation) {
            physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
              performEngineCreation(observed)
            }
          }
          awaitEngineTransition(observed.result)
        }
        is InternalState.Attaching -> {
          try {
            awaitEngineTransition(observed.result)
          } catch (_: MapLeaseInvalidatedException) {
            // Engine access survives presentation churn. Re-read the lifecycle to find the
            // retained engine or wait for its replacement.
          }
        }
        is InternalState.Attached -> return observed.engine
        is InternalState.Detaching -> {
          try {
            awaitEngineTransition(observed.result)
          } catch (_: MapLeaseInvalidatedException) {
            // A replacement presentation may have superseded this lease.
          }
        }
        is InternalState.OpenDetached ->
          observed.engine?.let {
            return it
          }
        is InternalState.Closing,
        InternalState.Closed ->
          throw CancellationException("The map closed before engine access could begin")
      }
    }
  }

  private suspend fun <T> awaitEngineTransition(result: CompletableDeferred<Result<T>>): T {
    val outcome = result.await()
    val failure = outcome.exceptionOrNull()
    val state = current.load()
    if (
      failure != null &&
        failure !is CancellationException &&
        failure !is Error &&
        (state is InternalState.Closing || state === InternalState.Closed)
    ) {
      throw CancellationException(
        "The map closed before engine access could begin",
        failure,
      )
    }
    return outcome.getOrThrow()
  }

  private suspend fun performEngineCreation(creating: InternalState.CreatingEngine) {
    val outcome = runCatching {
      adapter.createEngine(creating.engine)
      creating.engineCreated.store(true)
      creating.engine
    }
    outcome.exceptionOrNull()?.let { failure ->
      runCatching { adapter.destroyEngine(creating.engine) }
        .exceptionOrNull()
        ?.let(failure::addSuppressed)
      currentStyle.store(null)
      currentStyleRequest.store(null)
    }
    current.compareAndSet(creating, InternalState.OpenDetached(outcome.getOrNull()))
    creating.result.complete(outcome)
  }

  private fun createAttachmentStart(observed: InternalState.OpenDetached): AttachmentStart {
    val result = CompletableDeferred<Result<RenderLease>>()
    val engine = EngineMapIdentity(nextIdentity.incrementAndFetch())
    val lease = RenderLease(nextIdentity.incrementAndFetch())
    val selected =
      InternalState.Attaching(
        engine = observed.engine ?: engine,
        lease = lease,
        result = result,
        engineCreated = AtomicBoolean(observed.engine != null),
      )
    return AttachmentStart(
      state = selected,
      createEngine = observed.engine == null,
      pending = PendingAttachment(lease, result),
    )
  }

  private fun launchAttachment(start: AttachmentStart) {
    physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
      performAttach(start.state, start.createEngine)
    }
  }

  /** Replaces a destroy-on-detach engine without transferring its current presentation lease. */
  fun beginEngineReplacement(engine: EngineMapIdentity, lease: RenderLease): Boolean {
    check(adapter.engineRetention == EngineRetention.DESTROY) {
      "Retained engines do not need same-presentation replacement"
    }
    val result = CompletableDeferred<Result<RenderLease>>()
    val replacement = EngineMapIdentity(nextIdentity.incrementAndFetch())
    val observed = current.load()
    if (observed !is InternalState.Attached) return false
    if (observed.engine != engine || observed.lease != lease) return false
    val replacing =
      InternalState.Attaching(
        engine = replacement,
        lease = lease,
        result = result,
        engineCreated = AtomicBoolean(false),
      )
    if (!current.compareAndSet(observed, replacing)) return false
    currentStyle.store(null)
    currentStyleRequest.store(null)
    physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
      performEngineReplacement(engine, replacing)
    }
    return true
  }

  /** Commits logical closure synchronously and starts one cancellation-independent cleanup. */
  fun close() {
    val closing: InternalState.Closing
    while (true) {
      val observed = current.load()
      if (observed is InternalState.Closing || observed === InternalState.Closed) return
      val next = InternalState.Closing(observed)
      if (current.compareAndSet(observed, next)) {
        closing = next
        break
      }
    }
    onClosing(this)
    physicalScope.launch(start = CoroutineStart.UNDISPATCHED) { performClose(closing) }
  }

  /** Waits for every cleanup attempt and reports their combined outcome. */
  suspend fun awaitClosed() {
    closure.await().getOrThrow()
  }

  /** Invalidates [lease] before awaiting physical detachment. Stale leases have no effect. */
  suspend fun detach(lease: RenderLease): Boolean {
    val result = beginDetach(lease) ?: return false
    result.await().getOrThrow()
    return true
  }

  /** Detaches the current presentation, or joins a detachment that already started. */
  suspend fun detachCurrentPresentation(): Boolean {
    val observed = current.load()
    return when (observed) {
      is InternalState.Attaching -> detach(observed.lease)
      is InternalState.Attached -> detach(observed.lease)
      is InternalState.Detaching -> {
        observed.result.await().getOrThrow()
        true
      }
      is InternalState.OpenDetached,
      is InternalState.CreatingEngine,
      is InternalState.Closing,
      InternalState.Closed -> false
    }
  }

  private fun beginDetach(lease: RenderLease): CompletableDeferred<Result<Unit>>? {
    while (true) {
      val observed = current.load()
      val detaching =
        when (observed) {
          is InternalState.Attaching -> {
            if (observed.lease != lease) return null
            InternalState.Detaching(
              engine = observed.engine,
              lease = lease,
              result = CompletableDeferred(),
              attachResult = observed.result,
              engineCreated = observed.engineCreated,
            )
          }
          is InternalState.Attached -> {
            if (observed.lease != lease) return null
            InternalState.Detaching(
              engine = observed.engine,
              lease = lease,
              result = CompletableDeferred(),
              attachResult = null,
              engineCreated = null,
            )
          }
          else -> return null
        }
      if (!current.compareAndSet(observed, detaching)) continue
      physicalScope.launch(start = CoroutineStart.UNDISPATCHED) { performDetach(detaching) }
      return detaching.result
    }
  }

  /** Accepts a viewport-bound event only for the currently attached engine and lease. */
  fun acceptPresentationEvent(
    engine: EngineMapIdentity,
    lease: RenderLease,
    event: () -> Unit,
  ): Boolean {
    val observed = current.load()
    if (observed !is InternalState.Attached) return false
    if (observed.engine != engine || observed.lease != lease) return false
    event()
    return true
  }

  private suspend fun performAttach(attaching: InternalState.Attaching, createEngine: Boolean) {
    var attachAttempted = false
    val outcome =
      try {
        if (createEngine) {
          adapter.createEngine(attaching.engine)
          attaching.engineCreated.store(true)
        }
        attachAttempted = true
        adapter.attach(attaching.engine, attaching.lease)
        Result.success(attaching.lease)
      } catch (error: Throwable) {
        Result.failure(error)
      }
    val stillAttaching = current.load() === attaching
    if (outcome.isSuccess && stillAttaching) {
      val committed =
        current.compareAndSet(attaching, InternalState.Attached(attaching.engine, attaching.lease))
      if (committed) {
        attaching.result.complete(outcome)
      } else {
        attaching.result.complete(Result.failure(MapLeaseInvalidatedException()))
      }
    } else if (outcome.isFailure && stillAttaching) {
      val error = checkNotNull(outcome.exceptionOrNull())
      if (current.load() !== attaching) {
        attaching.result.complete(
          Result.failure(MapLeaseInvalidatedException().also { error.let(it::addSuppressed) })
        )
        return
      }
      if (attachAttempted) {
        runCatching { adapter.detach(attaching.engine, attaching.lease) }
          .exceptionOrNull()
          ?.let(error::addSuppressed)
      }
      if (current.load() !== attaching) {
        attaching.result.complete(
          Result.failure(MapLeaseInvalidatedException().also { error.let(it::addSuppressed) })
        )
        return
      }
      val destroyEngine =
        !attaching.engineCreated.load() || adapter.engineRetention == EngineRetention.DESTROY
      if (destroyEngine) {
        runCatching { adapter.destroyEngine(attaching.engine) }
          .exceptionOrNull()
          ?.let(error::addSuppressed)
        currentStyle.store(null)
        currentStyleRequest.store(null)
      }
      current.compareAndSet(
        attaching,
        InternalState.OpenDetached(attaching.engine.takeIf { !destroyEngine }),
      )
      attaching.result.complete(Result.failure(error))
    } else {
      val invalidated = MapLeaseInvalidatedException()
      outcome.exceptionOrNull()?.let(invalidated::addSuppressed)
      attaching.result.complete(Result.failure(invalidated))
    }
  }

  private suspend fun performEngineReplacement(
    previousEngine: EngineMapIdentity,
    attaching: InternalState.Attaching,
  ) {
    val failure = runCatching {
      adapter.destroyEngine(previousEngine)
      adapter.createEngine(attaching.engine)
      attaching.engineCreated.store(true)
      adapter.attach(attaching.engine, attaching.lease)
    }
      .exceptionOrNull()
    if (failure == null) {
      val committed =
        current.compareAndSet(attaching, InternalState.Attached(attaching.engine, attaching.lease))
      attaching.result.complete(
        if (committed) Result.success(attaching.lease)
        else Result.failure(MapLeaseInvalidatedException())
      )
      return
    }

    if (attaching.engineCreated.load()) {
      runCatching { adapter.detach(attaching.engine, attaching.lease) }
        .exceptionOrNull()
        ?.let(failure::addSuppressed)
      runCatching { adapter.destroyEngine(attaching.engine) }
        .exceptionOrNull()
        ?.let(failure::addSuppressed)
    }
    current.compareAndSet(attaching, InternalState.OpenDetached(null))
    attaching.result.complete(Result.failure(failure))
  }

  private suspend fun performDetach(detaching: InternalState.Detaching) {
    detaching.attachResult?.await()
    val failures = mutableListOf<Throwable>()
    collectFailure(failures) { adapter.detach(detaching.engine, detaching.lease) }
    val engineCreated = detaching.engineCreated?.load() ?: true
    val destroyEngine = adapter.engineRetention == EngineRetention.DESTROY || !engineCreated
    if (destroyEngine) {
      collectFailure(failures) { adapter.destroyEngine(detaching.engine) }
      currentStyle.store(null)
      currentStyleRequest.store(null)
    }
    val outcome = failures.cleanupResult("Map")
    val nextEngine =
      detaching.engine.takeIf { adapter.engineRetention == EngineRetention.RETAIN && engineCreated }
    current.compareAndSet(detaching, InternalState.OpenDetached(nextEngine))
    detaching.result.complete(outcome)
  }

  private suspend fun performClose(closing: InternalState.Closing) {
    val previous = closing.previous
    val failures = mutableListOf<Throwable>()

    when (previous) {
      is InternalState.CreatingEngine -> previous.result.await()
      is InternalState.Attaching -> {
        previous.result.await()
        collectFailure(failures) { adapter.detach(previous.engine, previous.lease) }
      }
      is InternalState.Attached ->
        collectFailure(failures) { adapter.detach(previous.engine, previous.lease) }
      is InternalState.Detaching ->
        previous.result.await().exceptionOrNull()?.let { addCleanupFailure(failures, it) }
      is InternalState.OpenDetached -> Unit
      is InternalState.Closing,
      InternalState.Closed -> error("Closure cannot start from ${previous::class.simpleName}")
    }

    val engine =
      if (previous is InternalState.CreatingEngine && !previous.engineCreated.load()) null
      else previous.engine
    val detachAlreadyDestroyedEngine =
      previous is InternalState.Detaching && adapter.engineRetention == EngineRetention.DESTROY
    if (engine != null && !detachAlreadyDestroyedEngine) {
      collectFailure(failures) { adapter.destroyEngine(engine) }
    }
    currentStyle.store(null)
    currentStyleRequest.store(null)
    collectFailure(failures) { adapter.closeResources() }

    current.compareAndSet(closing, InternalState.Closed)
    closure.complete(failures.cleanupResult("Map"))
  }

  private suspend fun collectFailure(
    failures: MutableList<Throwable>,
    cleanup: suspend () -> Unit,
  ) {
    runCatching { cleanup() }.exceptionOrNull()?.let(failures::add)
  }

  private fun addCleanupFailure(failures: MutableList<Throwable>, failure: Throwable) {
    failures.addCleanupFailure(failure)
  }

  private data class StyleClaim(val engine: EngineMapIdentity, val style: StyleIdentity)

  private data class StyleRequestClaim(
    val engine: EngineMapIdentity,
    val request: StyleRequestIdentity,
  )

  private data class AttachmentStart(
    val state: InternalState.Attaching,
    val createEngine: Boolean,
    val pending: PendingAttachment,
  )

  private sealed interface InternalState {
    val engine: EngineMapIdentity?

    data class OpenDetached(override val engine: EngineMapIdentity?) : InternalState

    data class CreatingEngine(
      override val engine: EngineMapIdentity,
      val result: CompletableDeferred<Result<EngineMapIdentity>>,
      val engineCreated: AtomicBoolean,
    ) : InternalState

    data class Attaching(
      override val engine: EngineMapIdentity,
      val lease: RenderLease,
      val result: CompletableDeferred<Result<RenderLease>>,
      val engineCreated: AtomicBoolean,
    ) : InternalState

    data class Attached(
      override val engine: EngineMapIdentity,
      val lease: RenderLease,
    ) : InternalState

    data class Detaching(
      override val engine: EngineMapIdentity,
      val lease: RenderLease,
      val result: CompletableDeferred<Result<Unit>>,
      val attachResult: CompletableDeferred<Result<RenderLease>>?,
      val engineCreated: AtomicBoolean?,
    ) : InternalState

    data class Closing(val previous: InternalState) : InternalState {
      override val engine: EngineMapIdentity? = previous.engine
    }

    data object Closed : InternalState {
      override val engine: EngineMapIdentity? = null
    }
  }
}

/**
 * Runs [block] on [dispatcher], inline when no dispatch is needed. Either way the block runs on the
 * main thread that [guard] pins, and the guard learns that thread from the first block it runs.
 */
private fun postToMain(dispatcher: CoroutineDispatcher, guard: MainThreadGuard, block: () -> Unit) {
  if (dispatcher.isDispatchNeeded(EmptyCoroutineContext)) {
    dispatcher.dispatch(
      EmptyCoroutineContext,
      Runnable {
        guard.pin()
        block()
      },
    )
  } else {
    guard.requireMain()
    block()
  }
}
