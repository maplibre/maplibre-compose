@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.internal.CameraInputAuthority

/**
 * Owns the logical lifecycle of one [MapState]: presentation reservation, publication, release,
 * closure, and the sessions it adopts.
 *
 * Decisions run on the main thread. Sessions run their own physical work, and this authority waits
 * for it on [physicalScope] before it returns to main to commit. Engine threads read an
 * [accessView] instead of taking a lock, and [close] may be called from any thread because it only
 * posts.
 */
internal class MapLifecycleAuthority(
  private val owner: MapState,
  private val physicalScope: CoroutineScope,
  private val mainDispatcher: CoroutineDispatcher,
  private val mainThread: MainThreadGuard,
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

  /** Sessions that map closure closes. Each leaves when it reports [sessionClosing]. */
  private val sessions = linkedSetOf<MapAdapter>()
  private val closure = CompletableDeferred<Result<Unit>>()
  private var attachment: Attachment? = null
  private var retainedAdapter: MapAdapter? = null
  private val retiringAdapters = linkedSetOf<MapAdapter>()
  private val releaseCleanups = linkedSetOf<CompletableDeferred<Result<Unit>>>()
  private val pendingCleanupFailures = mutableListOf<Throwable>()

  /** Whether the closure has committed on main. [isClosed] turns true before it does. */
  private val closed: Boolean
    get() = owner.attachmentAuthority.isClosed

  /** Set by [close] on any thread before the closure commits on main. */
  private val closeRequested = AtomicBoolean(false)

  /**
   * What engine threads may read: the adapters that may still report. Rebuilt on main after every
   * change, so a reader sees one consistent state.
   */
  @Volatile private var accessView = AccessView()

  /**
   * Platform callbacks run on engine threads; closure and adapter retirement run on main. They
   * agree under this lock that a callback in progress finishes before its adapter is torn down.
   */
  private val platformAccessLock = reentrantLock()
  private val platformAccessDepth = mutableMapOf<MapAdapter, Int>()
  private var closeAfterPlatformAccess = false
  private val retireAfterPlatformAccess = mutableSetOf<MapAdapter>()

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
    // The close request already refuses new callbacks; the commit waits for the running ones.
    val deferred = platformAccessLock.withLock {
      val running = platformAccessDepth.isNotEmpty()
      if (running) closeAfterPlatformAccess = true
      running
    }
    if (deferred) return
    val maps = buildSet {
      retainedAdapter?.let(::add)
      attachment?.adapter?.let(::add)
      addAll(retiringAdapters)
      addAll(sessions)
    }
    val recordedFailures = pendingCleanupFailures.toList()
    val releases = releaseCleanups.toList()
    attachment = null
    retainedAdapter = null
    retiringAdapters.clear()
    sessions.clear()
    pendingCleanupFailures.clear()
    publishAccessView()
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
    publishAccessView()
    replaced?.let { adapter ->
      physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
        runCatching { adapter.detachPresentation() }
      }
    }
    return token
  }

  /** Whether a presentation that [ownerToken] does not own holds this map and is not releasing. */
  fun isPresentedByOtherOwner(ownerToken: MapPresentationOwnerToken): Boolean {
    requireMain()
    val current = attachment ?: return false
    return !current.releasing && current.owner !== ownerToken
  }

  fun publishPresentation(token: MapPresentationToken, adapter: MapAdapter) {
    requireMain()
    if (closed) return
    val current = attachment
    check(current?.token == token && !current.releasing) {
      "The map presentation reservation is no longer current"
    }
    if (current.adapter === adapter && owner.currentMapAttachment?.adapter === adapter) return
    if (!selectAdapter(current, adapter)) return
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
    retainedToReplace?.let { retired ->
      // Its closure is watched below, so its own closing report must not retire it again.
      sessions.remove(retired)
      retiringAdapters += retired
    }
    owner.attachmentAuthority.commitPresentation(token = token, adapter = adapter)
    publishAccessView()
    owner.attachmentAuthority.seedPresentationViewport(token, adapter)
    if (retainedToReplace != null) {
      retireAdapter(retainedToReplace)
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
    publishAccessView()
    owner.attachmentAuthority.invalidatePresentation(current.adapter)
    val closingAdapter = current.adapter
    if (closingAdapter == null) {
      if (attachment === current) attachment = null
      publishAccessView()
      return
    }
    val completion = CompletableDeferred<Result<Unit>>().also(releaseCleanups::add)
    physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
      val result = runCatching { closingAdapter.detachPresentation() }
      withContext(mainDispatcher) {
        releaseCleanups.remove(completion)
        if (!closed) result.exceptionOrNull()?.let(pendingCleanupFailures::add)
        if (attachment === current) attachment = null
        publishAccessView()
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
    return selectAdapter(current, adapter)
  }

  /** Whether [adapter] is the presentation or retained engine. Readable from any thread. */
  fun acceptsAdapter(adapter: MapAdapter): Boolean {
    val view = accessView
    return !closeRequested.load() && (view.presentation === adapter || view.retained === adapter)
  }

  fun isPendingPublication(adapter: MapAdapter): Boolean {
    requireMain()
    return !closed && attachment?.adapter === adapter && attachment?.releasing == false
  }

  /** Whether [adapter] is the published presentation. Main thread only. */
  fun acceptsPresentation(adapter: MapAdapter): Boolean =
    !closeRequested.load() && owner.currentMapAttachment?.adapter === adapter

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
      if (!adopt(adapter)) throw MapClosedException()
      retainedAdapter = adapter
      publishAccessView()
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

  /**
   * Runs a platform callback on the calling engine thread. Closure and the retirement of [adapter]
   * wait for it; a close request made before it starts rejects it.
   */
  fun acceptEnginePlatformAccess(adapter: MapAdapter, event: () -> Unit): Boolean =
    acceptPlatformAccess(adapter, accepts = { acceptsAdapter(adapter) }, event)

  fun acceptPresentationPlatformAccess(adapter: MapAdapter, event: () -> Unit): Boolean =
    acceptPlatformAccess(adapter, accepts = { acceptsPresentation(adapter) }, event)

  /**
   * Creates the lifecycle of [session], a session that keeps its engine between presentations. It
   * runs [steps] on this map's physical scope and reports its closure with [sessionClosing]. The
   * session is not adopted and no work starts.
   */
  fun createRetainedEngineLifecycle(
    steps: RetainedEngineSteps,
    session: MapAdapter,
  ): RetainedEngineLifecycle =
    RetainedEngineLifecycle(steps, physicalScope, mainThread) { sessionClosing(session) }

  /**
   * Adopts [session] so that map closure closes it and waits for its cleanup. Returns false if the
   * session is closing. If this map is closed, closes the session and returns false.
   */
  fun adopt(session: MapAdapter): Boolean {
    requireMain()
    if (session.isClosing) return false
    if (isClosed) {
      session.close()
      return false
    }
    sessions += session
    return true
  }

  /**
   * Reports that an adopted [session] started closing on its own. Callable from any thread. On
   * main, the session stops being the presentation or the retained engine, and a cleanup failure is
   * reported when this map closes. Reports for a session that is not adopted have no effect.
   */
  fun sessionClosing(session: MapAdapter) = postToMain {
    if (!sessions.remove(session)) return@postToMain
    val wasAttached = attachment?.adapter === session
    val wasRetained = retainedAdapter === session
    if (wasAttached) attachment = null
    if (wasRetained) retainedAdapter = null
    retiringAdapters += session
    publishAccessView()
    if (wasAttached || wasRetained) owner.attachmentAuthority.invalidateClosedAdapter(session)
    physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
      val failure = runCatching { session.awaitClosed() }.exceptionOrNull()
      withContext(mainDispatcher) {
        retiringAdapters.remove(session)
        if (failure != null && !closed) pendingCleanupFailures += failure
      }
    }
  }

  /** A close request rejects new work at once; it does not wait for the closure to commit. */
  private fun requireOpen() {
    check(!isClosed) { "The map state is closed" }
  }

  private fun selectAdapter(current: Attachment, adapter: MapAdapter): Boolean {
    check(current.adapter == null || current.adapter === adapter) {
      "The map state already has a presentation adapter"
    }
    if (!adopt(adapter)) return false
    if (current.adapter === adapter) return true
    current.adapter = adapter
    publishAccessView()
    if (retainedAdapter !== adapter) {
      // A density or backend change during attachment can return to the retained engine.
      owner.styleAuthority.beginStyleLoadForNewAdapter(
        retainedEngineOwnsStyle = retainedAdapter != null
      )
    }
    return true
  }

  private fun publishAccessView() {
    val current = attachment
    accessView =
      AccessView(
        presentation = current?.adapter?.takeUnless { current.releasing },
        retained = retainedAdapter,
      )
  }

  private fun acceptPlatformAccess(
    adapter: MapAdapter,
    accepts: () -> Boolean,
    event: () -> Unit,
  ): Boolean {
    var commitDeferredClose = false
    var retire = false
    try {
      platformAccessLock.withLock {
        if (!accepts()) return false
        platformAccessDepth[adapter] = (platformAccessDepth[adapter] ?: 0) + 1
      }
      try {
        event()
      } finally {
        platformAccessLock.withLock {
          val depth = checkNotNull(platformAccessDepth[adapter]) - 1
          if (depth > 0) {
            platformAccessDepth[adapter] = depth
          } else {
            platformAccessDepth.remove(adapter)
            retire = retireAfterPlatformAccess.remove(adapter)
            if (platformAccessDepth.isEmpty() && closeAfterPlatformAccess) {
              closeAfterPlatformAccess = false
              commitDeferredClose = true
            }
          }
        }
      }
      return true
    } finally {
      if (retire) postToMain { adapter.close() }
      if (commitDeferredClose) postToMain { closeOnMain() }
    }
  }

  /** Closes a replaced adapter, after any platform callback that is running on it. */
  private fun retireAdapter(adapter: MapAdapter) {
    val deferred = platformAccessLock.withLock {
      val running = (platformAccessDepth[adapter] ?: 0) > 0
      if (running) retireAfterPlatformAccess += adapter
      running
    }
    if (!deferred) adapter.close()
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

  /** An immutable view for engine threads. [presentation] is null while it is releasing. */
  private class AccessView(val presentation: MapAdapter? = null, val retained: MapAdapter? = null)

  private companion object {
    val nextPresentationToken = AtomicLong(0L)
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
