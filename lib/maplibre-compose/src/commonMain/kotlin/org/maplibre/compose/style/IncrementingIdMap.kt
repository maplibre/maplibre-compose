package org.maplibre.compose.style

internal class IncrementingIdMap<T>(private val name: String) {
  private val ids = IncrementingId(name)
  private val map = mutableMapOf<T, String>()

  /** The values that hold an id, each paired with it. */
  val entries: Set<Map.Entry<T, String>>
    get() = map.entries

  fun addId(value: T): String {
    return map.getOrPut(value) { ids.next() }
  }

  fun getId(value: T): String {
    return map[value] ?: error("id not found for value")
  }

  fun removeId(value: T): String {
    return map.remove(value) ?: error("id not found for value")
  }
}
