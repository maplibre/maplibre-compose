package org.maplibre.compose.logging

import kotlin.jvm.JvmInline

/**
 * The component that produced a [MapLogRecord].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class MapLogSource private constructor(private val name: String) {
  override fun toString(): String = name

  public companion object {
    /** MapLibre Compose itself. */
    public val Library: MapLogSource = MapLogSource("Library")

    /** MapLibre Native, on Android, iOS, and desktop. */
    public val NativeEngine: MapLogSource = MapLogSource("NativeEngine")

    /** MapLibre GL JS, in the browser. */
    public val WebEngine: MapLogSource = MapLogSource("WebEngine")
  }
}
