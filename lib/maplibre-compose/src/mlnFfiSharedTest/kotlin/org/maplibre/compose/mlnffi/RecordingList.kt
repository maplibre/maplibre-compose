package org.maplibre.compose.mlnffi

/**
 * A list that records what MapLibre's threads did, and that a test thread reads while they are
 * still recording.
 *
 * Every read takes a snapshot, so iterating this list is safe while another thread appends to it.
 */
internal class RecordingList<T> : AbstractMutableList<T>() {
  private val lock = MlnFfiLock()
  private val items = mutableListOf<T>()

  override val size: Int
    get() = lock.withLock { items.size }

  override fun get(index: Int): T = lock.withLock { items[index] }

  override fun set(index: Int, element: T): T = lock.withLock { items.set(index, element) }

  override fun add(element: T): Boolean = lock.withLock { items.add(element) }

  override fun add(index: Int, element: T) {
    lock.withLock { items.add(index, element) }
  }

  override fun removeAt(index: Int): T = lock.withLock { items.removeAt(index) }

  override fun clear() {
    lock.withLock { items.clear() }
  }

  override fun iterator(): MutableIterator<T> = lock.withLock { items.toMutableList() }.iterator()

  override fun toString(): String = lock.withLock { items.toString() }
}
