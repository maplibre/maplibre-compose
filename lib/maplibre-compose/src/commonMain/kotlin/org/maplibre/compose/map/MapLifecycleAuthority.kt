@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.jvm.JvmInline
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

/** Defines the externally observable states of one logical map lifecycle. */
internal sealed interface MapLifecycleState {
  data class OpenDetached(val engine: EngineMapIdentity?) : MapLifecycleState

  data class Attaching(val engine: EngineMapIdentity, val lease: RenderLease) : MapLifecycleState

  data class Attached(val engine: EngineMapIdentity, val lease: RenderLease) : MapLifecycleState

  data class Detaching(val engine: EngineMapIdentity, val lease: RenderLease) : MapLifecycleState

  data object Closing : MapLifecycleState

  data object Closed : MapLifecycleState
}

internal class MapAlreadyAttachedException :
  IllegalStateException("The map already has a presentation")

internal class MapLeaseInvalidatedException :
  IllegalStateException("The presentation lease ended before attachment completed")

internal class MapClosedException : IllegalStateException("The map is closed")

internal open class AggregateCleanupException(message: String, failures: List<Throwable>) :
  RuntimeException(message, failures.first()) {
  init {
    failures.drop(1).forEach(::addSuppressed)
  }
}

internal class MapLifecycleCleanupException internal constructor(val failures: List<Throwable>) :
  AggregateCleanupException("Map cleanup failed in ${failures.size} resource(s)", failures)

internal data class PendingAttachment(
  val lease: RenderLease,
  val completion: CompletableDeferred<Result<RenderLease>>,
)

/** Owns every logical and physical lifecycle transition for one [MapState]. */
internal class MapLifecycleAuthority(
  private val owner: MapState,
  private val physicalScope: CoroutineScope,
) {
  private val lock = reentrantLock()
  private val platforms = mutableMapOf<MapLifecycleSession, MapLifecycleBinding>()
  private val closure = CompletableDeferred<Result<Unit>>()
  private var attachment: Attachment? = null
  private var retainedAdapter: MapAdapter? = null
  private val retiringAdapters = linkedSetOf<MapAdapter>()
  private val releaseCleanups = linkedSetOf<CompletableDeferred<Result<Unit>>>()
  private val pendingCleanupFailures = mutableListOf<Throwable>()
  private var closed = false

  val isClosed: Boolean
    get() = serialized { closed }

  fun close() {
    val (maps, releases, recordedFailures) =
      serialized {
        if (closed) return
        closed = true
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
        owner.commitClosed()
        Triple(maps, releases, recordedFailures)
      }
    if (maps.isEmpty() && releases.isEmpty()) {
      val failures = mutableListOf<Throwable>()
      recordedFailures.forEach { addCleanupFailure(failures, it) }
      completeClosure(
        if (failures.isEmpty()) Result.success(Unit)
        else Result.failure(MapStateCleanupException(failures))
      )
      return
    }
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
      completeClosure(
        if (failures.isEmpty()) Result.success(Unit)
        else Result.failure(MapStateCleanupException(failures))
      )
    }
  }

  suspend fun awaitClosed() {
    closure.await().getOrThrow()
  }

  fun reservePresentation(ownerToken: MapPresentationOwnerToken): MapPresentationToken {
    var replaced: MapAdapter? = null
    val token = serialized {
      requireOpen()
      val current = attachment
      check(current == null || current.releasing || current.owner === ownerToken) {
        "The map state already has a presentation"
      }
      replaced = current?.adapter?.takeUnless { current.releasing }
      if (replaced != null) owner.invalidatePresentation(replaced)
      val token = MapPresentationToken(nextPresentationToken.incrementAndFetch())
      attachment = Attachment(ownerToken, token)
      token
    }
    replaced?.let { adapter ->
      physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
        runCatching { adapter.detachPresentation() }
      }
    }
    return token
  }

  fun publishPresentation(
    token: MapPresentationToken,
    adapter: MapAdapter,
    options: MapPresentationOptions,
  ) {
    val preparation = serialized {
      if (closed) return
      requireOpen()
      val current = attachment
      check(current?.token == token && !current.releasing) {
        "The map presentation reservation is no longer current"
      }
      if (current.adapter === adapter) {
        check(current.admitted) { "The map presentation is still being published" }
        owner.presentation?.updateOptions(options)
        return
      }
      check(current.adapter == null) { "The map state already has a presentation" }
      current.adapter = adapter
      val reusesRetainedAdapter = retainedAdapter === adapter
      val replaced = retainedAdapter?.takeUnless { retained ->
        retained === adapter || !adapter.retainsEngineBetweenPresentations
      }
      PresentationPreparation(reusesRetainedAdapter, replaced)
    }
    try {
      owner.configurePresentationAdapter(adapter)
    } catch (error: Throwable) {
      serialized {
        if (attachment?.token == token && attachment?.adapter === adapter) {
          attachment?.adapter = null
          attachment?.admitted = false
        }
      }
      throw error
    }
    val replaced = serialized {
      val current = attachment
      if (closed || current?.token != token || current.releasing || current.adapter !== adapter) {
        return
      }
      if (adapter.retainsEngineBetweenPresentations) retainedAdapter = adapter
      preparation.replaced?.let(retiringAdapters::add)
      current.admitted = true
      owner.commitPresentation(
        token = token,
        adapter = adapter,
        options = options,
        reusesRetainedAdapter = preparation.reusesRetainedAdapter,
      )
      preparation.replaced
    }
    if (replaced != null) {
      replaced.close()
      physicalScope.launch {
        val failure = runCatching { replaced.awaitClosed() }.exceptionOrNull()
        serialized {
          retiringAdapters.remove(replaced)
          if (failure != null && !closed) pendingCleanupFailures += failure
        }
      }
    }
  }

  fun releasePresentation(token: MapPresentationToken, adapter: MapAdapter?) {
    val release = serialized {
      val current = attachment ?: return
      if (current.token != token) return
      if (adapter != null && current.adapter !== adapter) return
      if (current.releasing) return
      current.releasing = true
      owner.invalidatePresentation(current.adapter)
      current.adapter?.let { closingAdapter ->
        ReleaseCleanup(
          closingAdapter,
          CompletableDeferred<Result<Unit>>().also(releaseCleanups::add),
        )
      }
    }
    if (release == null) {
      serialized { if (attachment?.token == token) attachment = null }
      return
    }
    physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
      val result = runCatching { release.adapter.detachPresentation() }
      serialized {
        releaseCleanups.remove(release.completion)
        if (!closed) result.exceptionOrNull()?.let(pendingCleanupFailures::add)
        if (attachment?.token == token) attachment = null
      }
      release.completion.complete(result)
    }
  }

  fun retainedAdapter(compatibilityKey: Any): MapAdapter? = serialized {
    retainedAdapter?.takeIf { adapter ->
      adapter.retainsEngineBetweenPresentations &&
        adapter.presentationCompatibilityKey == compatibilityKey
    }
  }

  fun acceptsAdapter(adapter: MapAdapter): Boolean = serialized {
    !closed &&
      ((attachment?.adapter === adapter && attachment?.admitted == true) ||
        retainedAdapter === adapter)
  }

  fun isPendingPublication(adapter: MapAdapter): Boolean = serialized {
    !closed && attachment?.adapter === adapter && attachment?.releasing == false
  }

  fun acceptsPresentation(adapter: MapAdapter): Boolean = serialized {
    !closed &&
      attachment?.adapter === adapter &&
      attachment?.admitted == true &&
      owner.presentation?.adapter === adapter
  }

  fun currentAdapter(): MapAdapter? = serialized { attachment?.adapter ?: retainedAdapter }

  fun isCurrent(token: MapPresentationToken, adapter: MapAdapter): Boolean = serialized {
    !closed &&
      attachment?.token == token &&
      attachment?.adapter === adapter &&
      attachment?.admitted == true
  }

  fun bind(adapter: MapLifecyclePlatformAdapter): MapLifecycleBinding {
    val session = adapter as? MapLifecycleSession
    val lifecycle = lock.withLock {
      session?.let(platforms::get)?.let {
        return it
      }
      MapLifecycleBinding(adapter, physicalScope) { binding ->
          if (session != null) retireClosingSession(session, binding)
        }
        .also { binding ->
          if (closed) binding.close() else if (session != null) platforms[session] = binding
        }
    }
    if (session != null) {
      physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
        val failure = runCatching { lifecycle.awaitClosed() }.exceptionOrNull()
        serialized {
          if (platforms[session] === lifecycle) platforms.remove(session)
          retiringAdapters.remove(session)
          if (failure != null && !closed) pendingCleanupFailures += failure
        }
      }
    }
    return lifecycle
  }

  private fun retireClosingSession(
    session: MapLifecycleSession,
    binding: MapLifecycleBinding,
  ) {
    serialized {
      if (platforms[session] === binding) platforms.remove(session)
      val wasAttached = attachment?.adapter === session
      val wasRetained = retainedAdapter === session
      if (wasAttached || wasRetained) owner.invalidateClosedAdapter(session)
      if (wasAttached) attachment = null
      if (wasRetained) retainedAdapter = null
      retiringAdapters += session
    }
  }

  inline fun <T> serialized(action: () -> T): T = lock.withLock(action)

  private fun requireOpen() {
    if (closed) throw MapStateClosedException()
  }

  private fun completeClosure(result: Result<Unit>) {
    if (closure.complete(result)) owner.runtime.childClosed(owner)
  }

  private fun addCleanupFailure(failures: MutableList<Throwable>, failure: Throwable) {
    if (failure is MapLifecycleCleanupException) {
      failure.failures.forEach { addCleanupFailure(failures, it) }
    } else if (failures.none { it === failure }) {
      failures += failure
    }
  }

  private class Attachment(
    val owner: MapPresentationOwnerToken,
    val token: MapPresentationToken,
    var adapter: MapAdapter? = null,
    var admitted: Boolean = false,
    var releasing: Boolean = false,
  )

  private data class PresentationPreparation(
    val reusesRetainedAdapter: Boolean,
    val replaced: MapAdapter?,
  )

  private data class ReleaseCleanup(
    val adapter: MapAdapter,
    val completion: CompletableDeferred<Result<Unit>>,
  )

  private companion object {
    val nextPresentationToken = AtomicLong(0L)
  }
}

/** Applies engine and render-lease transitions through platform commands. */
internal class MapLifecycleBinding(
  private val adapter: MapLifecyclePlatformAdapter,
  private val physicalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  private val onClosing: (MapLifecycleBinding) -> Unit = {},
) {
  private val nextIdentity = AtomicLong(0L)
  private val current = AtomicReference<InternalState>(InternalState.OpenDetached(null))
  private val currentStyle = AtomicReference<StyleClaim?>(null)
  private val currentStyleRequest = AtomicReference<StyleRequestClaim?>(null)
  private val lifecycleLock = reentrantLock()
  private val closure = CompletableDeferred<Result<Unit>>()

  val state: MapLifecycleState
    get() = current.load().publicState

  val engineIdentity: EngineMapIdentity?
    get() = current.load().engine

  val renderLease: RenderLease?
    get() = (current.load() as? InternalState.Attached)?.lease

  val styleIdentity: StyleIdentity?
    get() = currentStyle.load()?.style

  fun claimStyleRequestIdentity(engine: EngineMapIdentity): StyleRequestIdentity? = serialized {
    if (!acceptEngineIdentity(engine)) return@serialized null
    val identity = StyleRequestIdentity(nextIdentity.incrementAndFetch())
    currentStyle.store(null)
    currentStyleRequest.store(StyleRequestClaim(engine, identity))
    identity
  }

  fun acceptStyleRequestEvent(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
    event: () -> Unit,
  ): Boolean = serialized {
    if (!acceptEngineIdentity(engine)) return@serialized false
    if (currentStyleRequest.load() != StyleRequestClaim(engine, request)) return@serialized false
    event()
    true
  }

  val acceptsWork: Boolean
    get() {
      val observed = current.load()
      return observed !is InternalState.Closing && observed !== InternalState.Closed
    }

  /** Claims the next loaded-style generation for [engine], invalidating the preceding one. */
  fun claimStyleIdentity(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
  ): StyleIdentity? {
    return serialized {
      if (!acceptEngineIdentity(engine)) return@serialized null
      if (currentStyleRequest.load() != StyleRequestClaim(engine, request)) return@serialized null
      val identity = StyleIdentity(nextIdentity.incrementAndFetch())
      currentStyle.store(StyleClaim(engine, identity))
      identity
    }
  }

  /** Claims and delivers a loaded style as one serialized operation. */
  fun claimStyleIdentity(
    engine: EngineMapIdentity,
    request: StyleRequestIdentity,
    event: () -> Unit,
  ): StyleIdentity? = serialized {
    if (!acceptEngineIdentity(engine)) return@serialized null
    if (currentStyleRequest.load() != StyleRequestClaim(engine, request)) return@serialized null
    val identity = StyleIdentity(nextIdentity.incrementAndFetch())
    currentStyle.store(StyleClaim(engine, identity))
    event()
    identity
  }

  fun invalidateStyleIdentity(engine: EngineMapIdentity): Boolean {
    return serialized {
      if (!acceptEngineIdentity(engine)) return@serialized false
      val claimed = currentStyle.load() ?: return@serialized true
      if (claimed.engine != engine) return@serialized false
      currentStyle.store(null)
      true
    }
  }

  /** Accepts an engine-durable event, including while a retained native engine is detached. */
  fun acceptEngineEvent(engine: EngineMapIdentity, event: () -> Unit): Boolean {
    return serialized {
      if (!acceptEngineIdentity(engine)) return@serialized false
      event()
      true
    }
  }

  /** Accepts an event only from the current loaded style on the current engine map. */
  fun acceptStyleEvent(
    engine: EngineMapIdentity,
    style: StyleIdentity,
    event: () -> Unit,
  ): Boolean {
    return serialized {
      if (!acceptEngineIdentity(engine)) return@serialized false
      if (currentStyle.load() != StyleClaim(engine, style)) return@serialized false
      event()
      true
    }
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
    val start = serialized {
      val observed = current.load()
      when (observed) {
        is InternalState.OpenDetached -> createAttachmentStart(observed)
        is InternalState.Attaching,
        is InternalState.Attached,
        is InternalState.Detaching -> throw MapAlreadyAttachedException()
        is InternalState.Closing,
        InternalState.Closed -> throw MapClosedException()
      }
    }
    launchAttachment(start)
    return start.pending
  }

  /** Starts attachment atomically, or returns false after detachment or closure starts. */
  fun beginAttachIfOpen(): Boolean {
    var alreadyAttached = false
    val start = serialized {
      when (val observed = current.load()) {
        is InternalState.OpenDetached -> createAttachmentStart(observed)
        is InternalState.Attaching,
        is InternalState.Attached -> {
          alreadyAttached = true
          null
        }
        is InternalState.Detaching,
        is InternalState.Closing,
        InternalState.Closed -> null
      }
    }
    start?.let(::launchAttachment)
    return alreadyAttached || start != null
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
    current.store(selected)
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
    val replacing =
      serialized {
        val observed = current.load()
        if (observed !is InternalState.Attached) return@serialized null
        if (observed.engine != engine || observed.lease != lease) return@serialized null
        InternalState.Attaching(
            engine = replacement,
            lease = lease,
            result = result,
            engineCreated = AtomicBoolean(false),
          )
          .also { current.store(it) }
      } ?: return false
    currentStyle.store(null)
    currentStyleRequest.store(null)
    physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
      performEngineReplacement(engine, replacing)
    }
    return true
  }

  /** Commits logical closure synchronously and starts one cancellation-independent cleanup. */
  fun close() {
    val closing =
      serialized {
        val observed = current.load()
        if (observed is InternalState.Closing || observed === InternalState.Closed) {
          return@serialized null
        }
        val closing = InternalState.Closing(observed)
        current.store(closing)
        closing
      } ?: return
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
      is InternalState.Closing,
      InternalState.Closed -> false
    }
  }

  private fun beginDetach(lease: RenderLease): CompletableDeferred<Result<Unit>>? {
    val detaching =
      serialized {
        val observed = current.load()
        when (observed) {
          is InternalState.Attaching -> {
            if (observed.lease != lease) return null
            val result = CompletableDeferred<Result<Unit>>()
            val detaching =
              InternalState.Detaching(
                engine = observed.engine,
                lease = lease,
                result = result,
                attachResult = observed.result,
                engineCreated = observed.engineCreated,
              )
            current.store(detaching)
            detaching
          }
          is InternalState.Attached -> {
            if (observed.lease != lease) return null
            val result = CompletableDeferred<Result<Unit>>()
            val detaching =
              InternalState.Detaching(
                engine = observed.engine,
                lease = lease,
                result = result,
                attachResult = null,
                engineCreated = null,
              )
            current.store(detaching)
            detaching
          }
          else -> null
        }
      } ?: return null
    physicalScope.launch(start = CoroutineStart.UNDISPATCHED) { performDetach(detaching) }
    return detaching.result
  }

  /** Accepts a viewport-bound event only for the currently attached engine and lease. */
  fun acceptPresentationEvent(
    engine: EngineMapIdentity,
    lease: RenderLease,
    event: () -> Unit,
  ): Boolean {
    return serialized {
      val observed = current.load()
      if (observed !is InternalState.Attached) return@serialized false
      if (observed.engine != engine || observed.lease != lease) return@serialized false
      event()
      true
    }
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
    val stillAttaching = serialized { current.load() === attaching }
    if (outcome.isSuccess && stillAttaching) {
      val committed = serialized {
        if (current.load() === attaching) {
          current.store(InternalState.Attached(attaching.engine, attaching.lease))
          true
        } else {
          false
        }
      }
      if (committed) {
        attaching.result.complete(outcome)
      } else {
        attaching.result.complete(Result.failure(MapLeaseInvalidatedException()))
      }
    } else if (outcome.isFailure && stillAttaching) {
      val error = checkNotNull(outcome.exceptionOrNull())
      if (serialized { current.load() !== attaching }) {
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
      if (serialized { current.load() !== attaching }) {
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
      serialized {
        if (current.load() === attaching) {
          current.store(InternalState.OpenDetached(attaching.engine.takeIf { !destroyEngine }))
        }
      }
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
      val committed = serialized {
        if (current.load() !== attaching) return@serialized false
        current.store(InternalState.Attached(attaching.engine, attaching.lease))
        true
      }
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
    serialized {
      if (current.load() === attaching) current.store(InternalState.OpenDetached(null))
    }
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
    val outcome =
      if (failures.isEmpty()) Result.success(Unit)
      else Result.failure(MapLifecycleCleanupException(failures))
    val nextEngine =
      detaching.engine.takeIf { adapter.engineRetention == EngineRetention.RETAIN && engineCreated }
    serialized {
      if (current.load() === detaching) current.store(InternalState.OpenDetached(nextEngine))
    }
    detaching.result.complete(outcome)
  }

  private suspend fun performClose(closing: InternalState.Closing) {
    val previous = closing.previous
    val failures = mutableListOf<Throwable>()

    when (previous) {
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
      InternalState.Closed -> error("Closure cannot start from ${previous.publicState}")
    }

    val engine = previous.engine
    val detachAlreadyDestroyedEngine =
      previous is InternalState.Detaching && adapter.engineRetention == EngineRetention.DESTROY
    if (engine != null && !detachAlreadyDestroyedEngine) {
      collectFailure(failures) { adapter.destroyEngine(engine) }
    }
    currentStyle.store(null)
    currentStyleRequest.store(null)
    collectFailure(failures) { adapter.closeResources() }

    serialized { if (current.load() === closing) current.store(InternalState.Closed) }
    closure.complete(
      if (failures.isEmpty()) Result.success(Unit)
      else Result.failure(MapLifecycleCleanupException(failures))
    )
  }

  private suspend fun collectFailure(
    failures: MutableList<Throwable>,
    cleanup: suspend () -> Unit,
  ) {
    runCatching { cleanup() }.exceptionOrNull()?.let(failures::add)
  }

  private fun addCleanupFailure(failures: MutableList<Throwable>, failure: Throwable) {
    if (failure is MapLifecycleCleanupException) failures += failure.failures
    else failures += failure
  }

  /** Serializes non-suspending lifecycle commits with callback validation and delivery. */
  private inline fun <T> serialized(action: () -> T): T = lifecycleLock.withLock(action)

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
    val publicState: MapLifecycleState

    data class OpenDetached(override val engine: EngineMapIdentity?) : InternalState {
      override val publicState = MapLifecycleState.OpenDetached(engine)
    }

    data class Attaching(
      override val engine: EngineMapIdentity,
      val lease: RenderLease,
      val result: CompletableDeferred<Result<RenderLease>>,
      val engineCreated: AtomicBoolean,
    ) : InternalState {
      override val publicState = MapLifecycleState.Attaching(engine, lease)
    }

    data class Attached(
      override val engine: EngineMapIdentity,
      val lease: RenderLease,
    ) : InternalState {
      override val publicState = MapLifecycleState.Attached(engine, lease)
    }

    data class Detaching(
      override val engine: EngineMapIdentity,
      val lease: RenderLease,
      val result: CompletableDeferred<Result<Unit>>,
      val attachResult: CompletableDeferred<Result<RenderLease>>?,
      val engineCreated: AtomicBoolean?,
    ) : InternalState {
      override val publicState = MapLifecycleState.Detaching(engine, lease)
    }

    data class Closing(val previous: InternalState) : InternalState {
      override val engine: EngineMapIdentity? = previous.engine
      override val publicState = MapLifecycleState.Closing
    }

    data object Closed : InternalState {
      override val engine: EngineMapIdentity? = null
      override val publicState = MapLifecycleState.Closed
    }
  }
}
