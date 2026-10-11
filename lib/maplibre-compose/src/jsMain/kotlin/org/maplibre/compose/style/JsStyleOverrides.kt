package org.maplibre.compose.style

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonNull
import org.maplibre.compose.map.MapOptionsDsl
import org.maplibre.compose.style.internal.StyleOverrideDefinition

@Immutable
public actual data class StyleOverrides
private constructor(
  public actual val light: Light?,
  /** The complete sky object to use. Null leaves the base sky unchanged unless [skyRemoved]. */
  public val sky: Sky?,
  /** Whether to remove the base style's sky. */
  public val skyRemoved: Boolean,
  /** The projection to use. Null leaves the base style's projection unchanged. */
  public val projection: Projection?,
  /** The terrain to use. Null leaves the base terrain unchanged unless [terrainRemoved]. */
  public val terrain: WebTerrain?,
  /** Whether to remove the base style's terrain. */
  public val terrainRemoved: Boolean,
) {
  public actual constructor(
    from: StyleOverrides,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(
    builder.light,
    builder.sky,
    builder.skyRemoved,
    builder.projection,
    builder.terrain,
    builder.terrainRemoved,
  )

  internal actual fun definition(): StyleOverrideDefinition =
    StyleOverrideDefinition(
      light = light?.toJson(),
      sky = if (skyRemoved) JsonNull else sky?.toJson(),
      projection = projection?.toJson(),
      terrain = if (terrainRemoved) JsonNull else terrain?.toJson(),
      terrainSource = terrain?.managedSource,
    )

  @MapOptionsDsl
  public actual class Builder internal actual constructor(from: StyleOverrides) {
    public actual var light: Light? = from.light
    /** See [StyleOverrides.sky]. Setting null stops overriding the sky. */
    public var sky: Sky? = from.sky
      set(value) {
        field = value
        skyRemoved = false
      }

    internal var skyRemoved: Boolean = from.skyRemoved
    /** See [StyleOverrides.projection]. Null stops overriding the projection. */
    public var projection: Projection? = from.projection
    /** See [StyleOverrides.terrain]. Setting null stops overriding the terrain. */
    public var terrain: WebTerrain? = from.terrain
      set(value) {
        field = value
        terrainRemoved = false
      }

    internal var terrainRemoved: Boolean = from.terrainRemoved

    /** Removes the sky, including one defined by the base style. */
    public fun removeSky() {
      sky = null
      skyRemoved = true
    }

    /** Removes 3D terrain, including terrain defined by the base style. */
    public fun removeTerrain() {
      terrain = null
      terrainRemoved = true
    }
  }

  public actual companion object {
    public actual val None: StyleOverrides = StyleOverrides(null, null, false, null, null, false)
  }
}
