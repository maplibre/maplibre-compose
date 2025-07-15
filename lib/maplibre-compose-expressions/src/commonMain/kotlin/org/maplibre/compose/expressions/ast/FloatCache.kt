package org.maplibre.compose.expressions.ast

internal class FloatCache<T>(val init: (Float) -> T) {
  private val smallInts = List(SIZE) { init(it.toFloat()) }
  private val smallFloats = List(SIZE) { init(it.toFloat() * RESOLUTION) }

  operator fun get(float: Float): T {
    return when {
      float.isSmallInt() -> smallInts[float.toInt()]
      (float / RESOLUTION).isSmallInt() -> smallFloats[(float / RESOLUTION).toInt()]

      else -> init(float)
    }
  }

  companion object {
    const val SIZE = 512
    const val RESOLUTION = 0.05f

    internal fun Float.isSmallInt() = toInt().toFloat() == this && toInt().isSmallInt()

    internal fun Int.isSmallInt() = this in 0..<SIZE
  }
}
