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

/** Identifies one platform engine-map instance for exactly as long as that instance is alive. */
@JvmInline internal value class EngineMapIdentity(private val value: Long)

/** Identifies one temporary attachment of a logical map to a presentation. */
@JvmInline internal value class RenderLease(private val value: Long)

/** Identifies one loaded base-style generation on one engine map. */
@JvmInline internal value class StyleIdentity(private val value: Long)

/** Identifies one requested base-style generation before it has loaded. */
@JvmInline internal value class StyleRequestIdentity(private val value: Long)

/** Whether detaching a presentation also destroys its engine map. */
internal enum class EngineRetention {
  RETAIN,
  DESTROY,
}

/** Commands performed for the one authority that owns the logical-map lifecycle. */
internal interface MapLifecyclePlatformAdapter {
  val engineRetention: EngineRetention

  suspend fun createEngine(identity: EngineMapIdentity)

  suspend fun attach(identity: EngineMapIdentity, lease: RenderLease)

  suspend fun detach(identity: EngineMapIdentity, lease: RenderLease)

  suspend fun destroyEngine(identity: EngineMapIdentity)

  suspend fun closeResources()
}

/** The externally observable logical states of one map lifecycle. */
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

internal class MapLifecycleCleanupException internal constructor(val failures: List<Throwable>) :
  RuntimeException("Map cleanup failed in ${failures.size} resource(s)", failures.first()) {
  init {
    failures.drop(1).forEach(::addSuppressed)
  }
}

internal data class PendingAttachment(
  val lease: RenderLease,
  val completion: CompletableDeferred<Result<RenderLease>>,
)

/**
 * The serialized authority for engine creation, leased presentation attachment, and closure.
 * Platform adapters execute commands but do not decide or mirror the logical state.
 */
internal class MapLifecycleAuthority(
  private val adapter: MapLifecyclePlatformAdapter,
  private val physicalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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
    val result = CompletableDeferred<Result<RenderLease>>()
    val engine = EngineMapIdentity(nextIdentity.incrementAndFetch())
    val lease = RenderLease(nextIdentity.incrementAndFetch())
    val selected = serialized {
      val observed = current.load()
      when (observed) {
        is InternalState.OpenDetached -> {
          val selectedEngine = observed.engine ?: engine
          val selected =
            InternalState.Attaching(
              engine = selectedEngine,
              lease = lease,
              result = result,
              engineCreated = AtomicBoolean(observed.engine != null),
            )
          current.store(selected)
          selected to (observed.engine == null)
        }
        is InternalState.Attaching,
        is InternalState.Attached,
        is InternalState.Detaching -> throw MapAlreadyAttachedException()
        is InternalState.Closing,
        InternalState.Closed -> throw MapClosedException()
      }
    }
    physicalScope.launch(start = CoroutineStart.UNDISPATCHED) {
      performAttach(selected.first, selected.second)
    }
    return PendingAttachment(lease, result)
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
