package org.maplibre.compose.mlnffi

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * An [MlnFfiLock] that reports whether [owner] holds it.
 *
 * A runtime loop parks inside a native pump until a wake arrives, so it reaches that pump holding
 * no lock that a caller needs to post work and signal the wake source. A loop that parked under
 * such a lock would block every caller of the one call that could release it. The loops read
 * [isHeldByOwnerThread] before each pump, which turns that deadlock into a failed check at the line
 * that introduced it.
 *
 * The flag tracks [owner] alone, which is the only thread the check asks about. That thread is also
 * the only one that writes the flag and the only one that reads it, so the flag needs no
 * synchronization of its own.
 */
internal class MlnFfiOwnerLock(private val owner: MlnFfiOwnerThread) {
  private val delegate = MlnFfiLock()

  private var heldByOwner = false

  /** Whether [owner] holds this lock. Read from that thread. */
  val isHeldByOwnerThread: Boolean
    get() = heldByOwner

  fun lock() {
    delegate.lock()
    if (owner.isCurrent()) heldByOwner = true
  }

  fun unlock() {
    if (owner.isCurrent()) heldByOwner = false
    delegate.unlock()
  }
}

/** Runs [block] holding [this], and releases the lock however [block] ends. */
@OptIn(ExperimentalContracts::class)
internal inline fun <T> MlnFfiOwnerLock.withLock(block: () -> T): T {
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
  lock()
  try {
    return block()
  } finally {
    unlock()
  }
}
