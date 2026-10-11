package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import org.maplibre.compose.interaction.InteractionBindingsBuilder
import org.maplibre.compose.interaction.internal.InteractionBindings

@Immutable
public actual data class MapUiOptions
private constructor(
  public actual val loadColor: Color,
  internal actual val bindings: InteractionBindings,
  /** How Android presents the map. See [AndroidRenderMode]. */
  public val renderMode: AndroidRenderMode,
) {
  public actual constructor(
    from: MapUiOptions,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(builder.loadColor, builder.bindingsBuilder.build(), builder.renderMode)

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
