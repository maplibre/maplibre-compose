package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * How the map renders.
 *
 * Some settings exist on one platform only and are not available from common code. The camera
 * projection is available on Android, iOS, and desktop.
 *
 * @property maximumFps Caps how often the map renders. Null uses the display refresh rate.
 * @property tileLod How the map chooses tile zoom when the camera is pitched.
 * @property debug Overlays that show how the map renders.
 */
@Immutable
public class RenderOptions
private constructor(
  public val maximumFps: Int?,
  public val tileLod: TileLodOptions,
  public val debug: DebugOverlays,
  internal val platform: PlatformRenderOptions,
) {
  /** Edits [from]; omitted settings inherit. */
  public constructor(
    from: RenderOptions = Standard,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(builder.maximumFps, builder.tileLod, builder.debugBuilder.build(), builder.platform)

  init {
    require(maximumFps == null || maximumFps > 0) { "maximumFps must be positive" }
  }

  override fun equals(other: Any?): Boolean =
    other is RenderOptions &&
      maximumFps == other.maximumFps &&
      tileLod == other.tileLod &&
      debug == other.debug &&
      platform == other.platform

  override fun hashCode(): Int = listOf(maximumFps, tileLod, debug, platform).hashCode()

  @MapOptionsDsl
  public class Builder internal constructor(from: RenderOptions) {
    public var maximumFps: Int? = from.maximumFps
    public var tileLod: TileLodOptions = from.tileLod
    internal val debugBuilder = DebugOverlays.Builder(from.debug)
    internal var platform: PlatformRenderOptions = from.platform

    public fun debug(block: DebugOverlays.Builder.() -> Unit) {
      debugBuilder.apply(block)
    }
  }

  public companion object {
    /** The platform defaults. */
    public val Standard: RenderOptions =
      RenderOptions(null, TileLodOptions.Standard, DebugOverlays.None, PlatformRenderOptions())

    /** [Standard] with tile borders and collision boxes drawn. */
    public val Debug: RenderOptions =
      RenderOptions(Standard) {
        debug {
          tileBorders = true
          collisionBoxes = true
        }
      }
  }
}

/**
 * Overlays that show how the map renders. Some overlays exist on one platform only and are not
 * available from common code.
 *
 * @property tileBorders Draws the boundary of every tile the map is built from.
 * @property collisionBoxes Draws the boxes symbol placement uses to decide what to hide.
 */
@Immutable
public class DebugOverlays
private constructor(
  public val tileBorders: Boolean,
  public val collisionBoxes: Boolean,
  internal val platform: PlatformDebugOverlays,
) {
  override fun equals(other: Any?): Boolean =
    other is DebugOverlays &&
      tileBorders == other.tileBorders &&
      collisionBoxes == other.collisionBoxes &&
      platform == other.platform

  override fun hashCode(): Int = listOf(tileBorders, collisionBoxes, platform).hashCode()

  @MapOptionsDsl
  public class Builder internal constructor(from: DebugOverlays) {
    public var tileBorders: Boolean = from.tileBorders
    public var collisionBoxes: Boolean = from.collisionBoxes
    internal var platform: PlatformDebugOverlays = from.platform

    internal fun build(): DebugOverlays = DebugOverlays(tileBorders, collisionBoxes, platform)
  }

  internal companion object {
    val None: DebugOverlays = DebugOverlays(false, false, PlatformDebugOverlays())
  }
}
