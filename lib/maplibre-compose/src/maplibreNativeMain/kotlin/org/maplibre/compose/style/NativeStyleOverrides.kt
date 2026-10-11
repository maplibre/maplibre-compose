package org.maplibre.compose.style

import androidx.compose.runtime.Immutable
import org.maplibre.compose.map.MapOptionsDsl
import org.maplibre.compose.style.internal.StyleOverrideDefinition

@Immutable
public actual data class StyleOverrides private constructor(public actual val light: Light?) {
  public actual constructor(
    from: StyleOverrides,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block).light)

  internal actual fun definition(): StyleOverrideDefinition =
    StyleOverrideDefinition(light = light?.toJson())

  @MapOptionsDsl
  public actual class Builder internal actual constructor(from: StyleOverrides) {
    public actual var light: Light? = from.light
  }

  public actual companion object {
    public actual val None: StyleOverrides = StyleOverrides(null)
  }
}
