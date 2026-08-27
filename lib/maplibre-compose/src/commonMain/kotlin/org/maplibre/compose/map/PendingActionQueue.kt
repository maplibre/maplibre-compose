package org.maplibre.compose.map

/**
 * How a [PendingActionQueue] runs its critical sections. An engine whose adapter is called from
 * several threads supplies its own lock; a single-threaded engine supplies [SessionLock.None].
 */
internal interface SessionLock {
  fun <T> withLock(block: () -> T): T

  /** No synchronization: every call already happens on one thread. */
  object None : SessionLock {
    override fun <T> withLock(block: () -> T): T = block()
  }
}

/** A one-shot action of type [M], with the path it takes if it never gets to run. */
internal class PendingAction<M>(val run: (M) -> Unit, val abandon: () -> Unit = {})

/** What a gate predicate answers about an offered or flushed action. */
internal sealed interface PendingActionGate<out T> {
  /** Neither queued nor dispatched; the caller decides what refusal means. */
  data object Refused : PendingActionGate<Nothing>

  /** Kept in the queue for a later flush. */
  data object Held : PendingActionGate<Nothing>

  /** Dispatched to [target] now. */
  class Open<T>(val target: T) : PendingActionGate<T>
}

/**
 * Actions accepted before their target exists, gated by a caller-supplied predicate.
 *
 * Thread-agnostic: every queue operation takes [lock] exactly once and runs its gate predicate
 * inside that hold, so the fields the predicate reads must be guarded by the same lock the caller
 * supplies. The lock is not reentrant, so no queue operation may run inside another [lock] hold.
 *
 * [post] dispatches an open gate's action outside the lock; [flush] dispatches drained actions
 * inside it, so a flush publishes its gate's state change and the drained dispatches atomically.
 */
internal class PendingActionQueue<M, T>(
  private val lock: SessionLock = SessionLock.None,
  private val dispatch: (T, PendingAction<M>) -> Boolean,
) {
  private val pending = mutableListOf<PendingAction<M>>()

  /**
   * Queues or dispatches [action] as [gate] answers. Returns false only on a refused gate or a
   * refused dispatch; the action's abandon path is the caller's to take then.
   */
  fun post(action: PendingAction<M>, gate: () -> PendingActionGate<T>): Boolean {
    val answer = lock.withLock {
      val current = gate()
      if (current is PendingActionGate.Held) pending += action
      current
    }
    return when (answer) {
      is PendingActionGate.Refused -> false
      is PendingActionGate.Held -> true
      is PendingActionGate.Open -> dispatch(answer.target, action)
    }
  }

  /**
   * Runs [open] and, when it answers [PendingActionGate.Open], drains every queued action into
   * [dispatch] under the same lock hold, abandoning each action the dispatch refuses.
   */
  fun flush(open: () -> PendingActionGate<T>) {
    lock.withLock {
      val answer = open()
      if (answer is PendingActionGate.Open) {
        val drained = pending.toList()
        pending.clear()
        drained.forEach { action -> if (!dispatch(answer.target, action)) action.abandon() }
      }
    }
  }

  /**
   * Empties the queue after running [prepare] under the same lock hold, returning the drained
   * actions for the caller to abandon outside it.
   */
  fun drain(prepare: () -> Unit = {}): List<PendingAction<M>> = lock.withLock {
    prepare()
    pending.toList().also { pending.clear() }
  }

  /** Withdraws a queued action, reporting whether it was still queued. */
  fun remove(action: PendingAction<M>): Boolean = lock.withLock { pending.remove(action) }
}
