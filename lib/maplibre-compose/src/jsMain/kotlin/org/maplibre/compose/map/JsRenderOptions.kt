package org.maplibre.compose.map

/** Draws the camera's padding, which is where it considers its center to be. */
public var DebugOverlays.Builder.padding: Boolean
  get() = platform.padding
  set(value) {
    platform = platform.copy(padding = value)
  }

/** Draws the camera's padding, which is where it considers its center to be. */
public val DebugOverlays.padding: Boolean
  get() = platform.padding

/** Shades the map by how many times each pixel was drawn. */
public var DebugOverlays.Builder.overdrawInspector: Boolean
  get() = platform.overdrawInspector
  set(value) {
    platform = platform.copy(overdrawInspector = value)
  }

/** Shades the map by how many times each pixel was drawn. */
public val DebugOverlays.overdrawInspector: Boolean
  get() = platform.overdrawInspector

internal actual class PlatformRenderOptions actual constructor() {
  actual override fun equals(other: Any?): Boolean = other is PlatformRenderOptions

  actual override fun hashCode(): Int = 0
}

internal actual data class PlatformDebugOverlays(
  val padding: Boolean,
  val overdrawInspector: Boolean,
) {
  actual constructor() : this(padding = false, overdrawInspector = false)
}
