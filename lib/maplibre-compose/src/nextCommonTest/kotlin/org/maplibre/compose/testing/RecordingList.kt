@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.testing

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A list that records what MapLibre's threads did, and that a test thread reads while they are
 * still recording.
 *
 * Every read takes a snapshot, so iterating this list is safe while another thread appends to it.
 * Removal through an iterator fails, because it would reach the snapshot rather than this list;
 * that also rules out `remove`, `removeAll`, `retainAll`, and `removeIf`, which are built on it.
 */
internal class RecordingList<T> : AbstractMutableList<T>() {
  private val items = AtomicReference<List<T>>(emptyList())

  override val size: Int
    get() = items.load().size

  override fun get(index: Int): T = items.load()[index]

  override fun set(index: Int, element: T): T {
    while (true) {
      val current = items.load()
      val previous = current[index]
      val next = current.toMutableList().also { it[index] = element }
      if (items.compareAndSet(current, next)) return previous
    }
  }

  override fun add(element: T): Boolean {
    while (true) {
      val current = items.load()
      if (items.compareAndSet(current, current + element)) return true
    }
  }

  override fun add(index: Int, element: T) {
    while (true) {
      val current = items.load()
      val next = current.toMutableList().also { it.add(index, element) }
      if (items.compareAndSet(current, next)) return
    }
  }

  override fun removeAt(index: Int): T {
    while (true) {
      val current = items.load()
      val removed = current[index]
      val next = current.toMutableList().also { it.removeAt(index) }
      if (items.compareAndSet(current, next)) return removed
    }
  }

  override fun clear() {
    items.store(emptyList())
  }

  override fun iterator(): MutableIterator<T> = snapshotIterator()

  override fun listIterator(): MutableListIterator<T> = snapshotListIterator(0)

  override fun listIterator(index: Int): MutableListIterator<T> = snapshotListIterator(index)

  override fun toString(): String = items.load().toString()

  private fun snapshotIterator(): MutableIterator<T> {
    val snapshot = items.load().toMutableList().iterator()
    return object : MutableIterator<T> by snapshot {
      override fun remove(): Nothing = throw snapshotRemove()
    }
  }

  private fun snapshotListIterator(index: Int): MutableListIterator<T> {
    val snapshot = items.load().toMutableList().listIterator(index)
    return object : MutableListIterator<T> by snapshot {
      override fun remove(): Nothing = throw snapshotRemove()

      override fun add(element: T): Nothing = throw snapshotRemove()

      override fun set(element: T): Nothing = throw snapshotRemove()
    }
  }

  private fun snapshotRemove(): UnsupportedOperationException =
    UnsupportedOperationException(
      "A RecordingList read takes a snapshot, so a removal through its iterator would reach " +
        "that snapshot and leave the list itself unchanged. Remove by index instead."
    )
}
