package org.maplibre.compose.style

internal class ReferenceCounter<in T> {
  private val map = mutableMapOf<T, Int>()

  fun increment(value: T, onZeroToOne: () -> Unit) {
    val count = map[value]
    if (count == null) {
      map[value] = 1
      onZeroToOne()
    } else {
      map[value] = count + 1
    }
  }

  fun decrement(value: T, onOneToZero: () -> Unit) {
    val count = map[value] ?: error("decrementing below zero")
    if (count == 1) {
      map.remove(value)
      onOneToZero()
    } else {
      map[value] = count - 1
    }
  }
}
