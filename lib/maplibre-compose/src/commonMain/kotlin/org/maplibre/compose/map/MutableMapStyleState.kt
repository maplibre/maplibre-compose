package org.maplibre.compose.map

import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleOverrides

/**
 * Base-style and root-override commands for a map or snapshotter created outside
 * [rememberMapState].
 */
public class MutableMapStyleState internal constructor(private val style: MapStyleState) {
  /**
   * Desired root-object overrides. Applied to the current style and after each base-style load.
   *
   * Setting it throws [IllegalStateException] when [rememberMapState] owns the style.
   */
  public var overrides: StyleOverrides
    get() = style.overrides
    set(value) {
      check(!style.baseStyleDeclared) { "Style overrides are declared by rememberMapState" }
      style.updateOverrides(value)
    }

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
