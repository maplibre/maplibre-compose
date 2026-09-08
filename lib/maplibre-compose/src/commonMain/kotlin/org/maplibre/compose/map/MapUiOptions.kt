package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import org.maplibre.compose.interaction.InteractionBindingsBuilder
import org.maplibre.compose.interaction.internal.InteractionBindings

/**
 * Settings for the Compose UI that shows the map: what shows before the first frame, and what
 * gestures, scrolling, and keys do. The Android render mode is available from Android code only.
 *
 * @property loadColor Shown in place of the map until the first style can be presented. Transparent
 *   leaves the content behind the map visible.
 */
@Immutable
public class MapUiOptions
private constructor(
  public val loadColor: Color,
  internal val bindings: InteractionBindings,
  internal val platform: PlatformUiOptions,
) {
  /** Edits [from]; omitted settings inherit. */
  public constructor(
    from: MapUiOptions = Standard,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(builder.loadColor, builder.bindingsBuilder.build(), builder.platform)

  override fun equals(other: Any?): Boolean =
    other is MapUiOptions &&
      loadColor == other.loadColor &&
      bindings == other.bindings &&
      platform == other.platform

  override fun hashCode(): Int = listOf(loadColor, bindings, platform).hashCode()

  @MapOptionsDsl
  public class Builder internal constructor(from: MapUiOptions) {
    public var loadColor: Color = from.loadColor
    internal val bindingsBuilder = InteractionBindingsBuilder(from.bindings)
    internal var platform: PlatformUiOptions = from.platform

    /**
     * What pointer gestures, scrolling, keys, and rotary input do. A mapping block replaces its
     * family's table. A binding cannot enable a camera movement that
     * [org.maplibre.compose.interaction.MapInteractions] disallows.
     */
    public fun bindings(block: InteractionBindingsBuilder.() -> Unit) {
      bindingsBuilder.apply(block)
    }
  }

  public companion object {
    /** Standard bindings and a transparent placeholder. */
    public val Standard: MapUiOptions =
      MapUiOptions(Color.Transparent, InteractionBindings.standard(), PlatformUiOptions())

    /**
     * No bindings. Pointer, scroll, key, and rotary input does not reach the map, including feature
     * clicks.
     */
    public val None: MapUiOptions =
      MapUiOptions(Color.Transparent, InteractionBindings.none(), PlatformUiOptions())
  }
}
