package org.maplibre.compose.style

import androidx.compose.runtime.Immutable
import org.maplibre.compose.map.MapOptionsDsl
import org.maplibre.compose.style.internal.StyleOverrideDefinition

/**
 * Overrides of the base style's root objects, reapplied after each style load.
 *
 * A null [light] leaves the base style's light unchanged. Clearing an override restores the current
 * base style's value. Sky, projection, and terrain settings are available in browser source sets.
 *
 * @property light The complete light object to use instead of the base style's light.
 */
@Immutable
public expect class StyleOverrides {
  public val light: Light?

  /** Edits [from]; omitted settings inherit. */
  public constructor(from: StyleOverrides = None, block: Builder.() -> Unit)

  override fun equals(other: Any?): Boolean

  override fun hashCode(): Int

  override fun toString(): String

  internal fun definition(): StyleOverrideDefinition

  /** Edits root-object overrides. */
  @MapOptionsDsl
  public class Builder internal constructor(from: StyleOverrides) {
    /** See [StyleOverrides.light]. Null stops overriding the light. */
    public var light: Light?
  }

  public companion object {
    /** Uses the base style's root objects without overriding them. */
    public val None: StyleOverrides
  }
}
