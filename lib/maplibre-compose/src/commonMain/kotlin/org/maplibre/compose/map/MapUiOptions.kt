package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import org.maplibre.compose.interaction.InteractionBindingsBuilder
import org.maplibre.compose.interaction.internal.InteractionBindings

/**
 * Settings for the Compose UI that shows the map: what shows before the first frame, and what
 * gestures, scrolling, and keys do. The Android render mode is available in source sets whose
 * targets are all Android.
 *
 * @property loadColor Shown in place of the map until the first style can be presented. Transparent
 *   leaves the content behind the map visible.
 */
@Immutable
public expect class MapUiOptions {
  public val loadColor: Color
  internal val bindings: InteractionBindings

  /** Edits [from]; omitted settings inherit. */
  public constructor(from: MapUiOptions = Standard, block: Builder.() -> Unit)

  override fun equals(other: Any?): Boolean

  override fun hashCode(): Int

  override fun toString(): String

  @MapOptionsDsl
  public class Builder internal constructor(from: MapUiOptions) {
    public var loadColor: Color

    /**
     * What pointer gestures, scrolling, keys, and rotary input do. A mapping block replaces its
     * family's table. A binding cannot enable a camera movement that
     * [org.maplibre.compose.interaction.MapInteractions] disallows.
     */
    public fun bindings(block: InteractionBindingsBuilder.() -> Unit)
  }

  public companion object {
    /** Standard bindings and a transparent placeholder. */
    public val Standard: MapUiOptions
    /**
     * No bindings. Pointer, scroll, key, and rotary input does not reach the map, including feature
     * clicks.
     */
    public val None: MapUiOptions
  }
}
