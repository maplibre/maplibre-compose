@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import org.maplibre.compose.style.BaseStyle

/**
 * The requested base style versus the one last pushed to the map.
 *
 * [request] encodes the one legal ordering of a style switch: the old binding unloads first so no
 * descriptor writes into a style being replaced, the request is recorded, the null-style callback
 * disposes the composition whose sources and layers would otherwise recompose against base layers
 * being replaced, and exactly one apply is posted.
 *
 * Thread-agnostic: [requested] may be read from any thread, and everything else belongs to the
 * thread that applies styles.
 */
internal class RequestedStyleState(private val lock: SessionLock = newSessionLock()) {

  /** The requested style paired with its generation, so the applying thread reads them together. */
  class Requested(val style: BaseStyle, val generation: Long)

  private val requestedRef = AtomicReference<Requested?>(null)

  val requested: Requested?
    get() = requestedRef.load()

  private val generationCounter = AtomicLong(0L)

  /**
   * The generation of the current [requested] style. Engine callbacks report this value, so it is
   * the record's style generation when [request] received one, or the locally minted generation
   * otherwise.
   */
  val requestedGeneration: Long
    get() = requestedRef.load()?.generation ?: 0L

  /** Applying thread only; the style last pushed to the map. */
  var applied: BaseStyle? = null
    private set

  /** Applying thread only; the generation of [applied]. */
  var appliedGeneration: Long = 0L
    private set

  /**
   * Runs the switch sequence above, or nothing when [generation] is already the requested
   * generation. The same style with a newer generation is a new request.
   *
   * [generation] is the record's style generation. Pass 0 only from engine tests that call
   * [org.maplibre.compose.map.MapAdapter.setBaseStyle] without a [MapState]; those mint a local
   * generation so the request/applied pair still moves.
   */
  fun request(
    style: BaseStyle,
    generation: Long = 0L,
    unloadBinding: () -> Unit,
    clearStyle: () -> Unit,
    postApply: () -> Unit,
  ): Unit = lock.withLock {
    // The lock serializes the callbacks with publication: a racing request could otherwise
    // unload and clear the binding a newer, already-applied request published.
    val current = requestedRef.load()
    if (generation > 0L) {
      if (current?.generation == generation) return@withLock
    } else if (style == current?.style) {
      return@withLock
    }
    val nextGeneration = nextGeneration(generation)
    requestedRef.store(Requested(style, nextGeneration))
    unloadBinding()
    clearStyle()
    postApply()
  }

  /** Applying thread only: the request to push now, or null when it is already applied. */
  fun takeUnapplied(): Requested? = requested?.takeIf { it.generation != appliedGeneration }

  /**
   * Applying thread only: adopts the generation of a request whose style is already applied, so a
   * coalesced switch back to the applied style (A to B to A with no apply between) leaves no
   * generation that no load will ever publish. Returns the adopted generation, or null when there
   * is nothing to acknowledge.
   */
  fun acknowledgeAlreadyApplied(): Long? {
    val request = requested ?: return null
    if (request.style != applied || request.generation <= appliedGeneration) return null
    appliedGeneration = request.generation
    return request.generation
  }

  /** Applying thread only: records that [request]'s style reached the map. */
  fun markApplied(request: Requested) {
    applied = request.style
    appliedGeneration = request.generation
  }

  /** The map was destroyed, so the next map must be given the requested style again. */
  fun resetApplied() {
    applied = null
  }

  /**
   * Uses [generation] when the record supplied one, otherwise mints a local id. The minting counter
   * stays at or ahead of every accepted record generation so a later engine-only request cannot
   * reuse an earlier id.
   */
  private fun nextGeneration(generation: Long): Long {
    if (generation > 0L) {
      val current = generationCounter.load()
      if (generation > current) generationCounter.store(generation)
      return generation
    }
    return generationCounter.incrementAndFetch()
  }
}
