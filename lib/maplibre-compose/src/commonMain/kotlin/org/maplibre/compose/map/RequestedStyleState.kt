@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
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
internal class RequestedStyleState {

  /** The requested style paired with its generation, so the applying thread reads them together. */
  class Requested(val style: BaseStyle, val generation: Long)

  @Volatile
  var requested: Requested? = null
    private set

  private val generationCounter = AtomicLong(0L)

  /** The generation of the most recently requested style; each [request] bumps it. */
  val requestedGeneration: Long
    get() = generationCounter.load()

  /** Applying thread only; the style last pushed to the map. */
  var applied: BaseStyle? = null
    private set

  /** Applying thread only; the generation of [applied]. */
  var appliedGeneration: Long = 0L
    private set

  /** Runs the switch sequence above, or nothing when [style] is already the requested style. */
  fun request(
    style: BaseStyle,
    unloadBinding: () -> Unit,
    clearStyle: () -> Unit,
    postApply: () -> Unit,
  ) {
    if (style == requested?.style) return
    unloadBinding()
    requested = Requested(style, generationCounter.incrementAndFetch())
    clearStyle()
    postApply()
  }

  /** Applying thread only: the request to push now, or null when it is already applied. */
  fun takeUnapplied(): Requested? = requested?.takeIf { it.style != applied }

  /** Applying thread only: records that [request]'s style reached the map. */
  fun markApplied(request: Requested) {
    applied = request.style
    appliedGeneration = request.generation
  }

  /** The map was destroyed, so the next map must be given the requested style again. */
  fun resetApplied() {
    applied = null
  }
}
