package org.maplibre.compose.expressions.ast

internal class IntCache<T>(val init: (Int) -> T) {
  private val smallInts = List(SIZE) { init(it) }

  operator fun get(int: Int): T {
    return when {
      int.isSmallInt() -> smallInts[int]
      else -> init(int)
    }
  }

  companion object {
    const val SIZE = 512

    internal fun Int.isSmallInt() = this in 0..<SIZE
  }
}
