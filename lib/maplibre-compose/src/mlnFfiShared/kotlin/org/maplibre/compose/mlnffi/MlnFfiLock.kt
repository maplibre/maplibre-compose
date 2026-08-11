package org.maplibre.compose.mlnffi

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Mutual exclusion between the threads MapLibre calls on.
 *
 * A holder releases this lock before acquiring it again. Reentrance and ownership queries belong to
 * a lock type of their own, which this integration adds when something needs them.
 *
 * [fair] hands the lock to the longest waiter, which keeps one thread from starving another while
 * both answer a stream of MapLibre callbacks. It costs throughput, so it is off by default.
 */
internal expect class MlnFfiLock(fair: Boolean = false) {
  fun lock()

  fun unlock()
}

/** Runs [block] holding [this], and releases the lock however [block] ends. */
@OptIn(ExperimentalContracts::class)
internal inline fun <T> MlnFfiLock.withLock(block: () -> T): T {
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
  lock()
  try {
    return block()
  } finally {
    unlock()
  }
}
