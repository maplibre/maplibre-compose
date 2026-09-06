@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.testing

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Records cross-thread callbacks. Iteration reads an immutable snapshot. */
internal class RecordingList<T> : AbstractList<T>() {
  private val items = AtomicReference<List<T>>(emptyList())

  override val size: Int
    get() = items.load().size

  override fun get(index: Int): T = items.load()[index]

  operator fun plusAssign(element: T) {
    while (true) {
      val current = items.load()
      if (items.compareAndSet(current, current + element)) return
    }
  }

  fun clear() {
    items.store(emptyList())
  }

  override fun iterator(): Iterator<T> = items.load().iterator()

  override fun listIterator(): ListIterator<T> = items.load().listIterator()

  override fun listIterator(index: Int): ListIterator<T> = items.load().listIterator(index)

  override fun toString(): String = items.load().toString()
}
