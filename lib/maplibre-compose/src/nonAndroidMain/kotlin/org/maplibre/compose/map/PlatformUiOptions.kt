package org.maplibre.compose.map

internal actual class PlatformUiOptions actual constructor() {
  actual override fun equals(other: Any?): Boolean = other is PlatformUiOptions

  actual override fun hashCode(): Int = 0

  actual val fields: List<Pair<String, Any?>>
    get() = emptyList()
}
