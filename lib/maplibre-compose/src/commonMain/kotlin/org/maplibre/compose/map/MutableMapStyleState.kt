package org.maplibre.compose.map

import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleHandleException

/** Base-style commands for a map or snapshotter created outside [rememberMapState]. */
public class MutableMapStyleState internal constructor(private val style: MapStyleState) {
  /** Loads a new base style and replaces the current generation's resources. */
  public var baseStyle: BaseStyle
    get() = style.baseStyle
    set(value) {
      if (style.baseStyleDeclared) {
        throw StyleHandleException("The base style is declared by rememberMapState")
      }
      style.updateBaseStyle(value)
    }
}
