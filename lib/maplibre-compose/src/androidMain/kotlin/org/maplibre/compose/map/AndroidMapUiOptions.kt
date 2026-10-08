package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import org.maplibre.compose.interaction.InteractionBindingsBuilder
import org.maplibre.compose.interaction.internal.InteractionBindings
import org.maplibre.compose.map.internal.commonEquals
import org.maplibre.compose.map.internal.commonHashCode
import org.maplibre.compose.map.internal.commonToString

@Immutable
public actual class MapUiOptions
private constructor(
  public actual val loadColor: Color,
  internal actual val bindings: InteractionBindings,
  /** Which Android view draws the map. */
  public val renderMode: AndroidRenderMode,
) {
  public actual constructor(
    from: MapUiOptions,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(builder.loadColor, builder.bindingsBuilder.build(), builder.renderMode)

  actual override fun equals(other: Any?): Boolean =
    other is MapUiOptions && commonEquals(other) && renderMode == other.renderMode

  actual override fun hashCode(): Int = commonHashCode(renderMode.hashCode())

  actual override fun toString(): String = commonToString("renderMode" to renderMode)

  @MapOptionsDsl
  public actual class Builder internal actual constructor(from: MapUiOptions) {
    public actual var loadColor: Color = from.loadColor
    internal val bindingsBuilder = InteractionBindingsBuilder(from.bindings)

    /** See [MapUiOptions.renderMode]. */
    public var renderMode: AndroidRenderMode = from.renderMode

    public actual fun bindings(block: InteractionBindingsBuilder.() -> Unit) {
      bindingsBuilder.apply(block)
    }
  }

  public actual companion object {
    public actual val Standard: MapUiOptions =
      MapUiOptions(Color.Transparent, InteractionBindings.standard(), AndroidRenderMode.Surface)

    public actual val None: MapUiOptions =
      MapUiOptions(Color.Transparent, InteractionBindings.none(), AndroidRenderMode.Surface)
  }
}
