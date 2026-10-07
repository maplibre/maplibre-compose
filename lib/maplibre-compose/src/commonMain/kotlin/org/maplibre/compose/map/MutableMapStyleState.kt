package org.maplibre.compose.map

import org.maplibre.compose.style.BaseStyle

/** Base-style commands for a map or snapshotter created outside [rememberMapState]. */
public class MutableMapStyleState internal constructor(private val style: MapStyleState) {
  /**
   * Loads a new base style and replaces the current generation's resources.
   *
   * Setting it throws [IllegalStateException] when [rememberMapState] declares the base style.
   */
  public var baseStyle: BaseStyle
    get() = style.baseStyle
    set(value) {
      // The message leaves out the value: a style URL or JSON can contain an access token.
      check(!style.baseStyleDeclared) { "The base style is declared by rememberMapState" }
      style.updateBaseStyle(value)
    }
}
