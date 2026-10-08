package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import org.maplibre.compose.map.internal.validate

@Immutable
public actual data class RenderOptions
private constructor(
  public actual val maximumFps: Int?,
  public actual val tileLod: TileLodOptions,
  public actual val debug: DebugOverlays,
  /** The camera projection MapLibre Native renders with. */
  public val cameraProjection: CameraProjection,
) {
  public actual constructor(
    from: RenderOptions,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(
    builder.maximumFps,
    builder.tileLod,
    builder.debugBuilder.build(),
    builder.cameraProjection,
  )

  init {
    validate()
  }

  @MapOptionsDsl
  public actual class Builder internal actual constructor(from: RenderOptions) {
    public actual var maximumFps: Int? = from.maximumFps
    public actual var tileLod: TileLodOptions = from.tileLod
    internal val debugBuilder = DebugOverlays.Builder(from.debug)

    /** See [RenderOptions.cameraProjection]. */
    public var cameraProjection: CameraProjection = from.cameraProjection

    public actual fun debug(block: DebugOverlays.Builder.() -> Unit) {
      debugBuilder.apply(block)
    }
  }

  public actual companion object {
    public actual val Standard: RenderOptions =
      RenderOptions(null, TileLodOptions.Standard, DebugOverlays.None, CameraProjection.Perspective)

    public actual val Debug: RenderOptions =
      RenderOptions(Standard) {
        debug {
          tileBorders = true
          collisionBoxes = true
        }
      }
  }
}

@Immutable
public actual data class DebugOverlays
private constructor(
  public actual val tileBorders: Boolean,
  public actual val collisionBoxes: Boolean,
  /** Draws the time each tile was last updated. */
  public val tileTimestamps: Boolean,
  /** Draws tile parse state on each tile. */
  public val tileParseStatus: Boolean,
) {
  public actual constructor(
    from: DebugOverlays,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(
    builder.tileBorders,
    builder.collisionBoxes,
    builder.tileTimestamps,
    builder.tileParseStatus,
  )

  @MapOptionsDsl
  public actual class Builder internal actual constructor(from: DebugOverlays) {
    public actual var tileBorders: Boolean = from.tileBorders
    public actual var collisionBoxes: Boolean = from.collisionBoxes
    /** See [DebugOverlays.tileTimestamps]. */
    public var tileTimestamps: Boolean = from.tileTimestamps
    /** See [DebugOverlays.tileParseStatus]. */
    public var tileParseStatus: Boolean = from.tileParseStatus

    internal actual fun build(): DebugOverlays =
      DebugOverlays(tileBorders, collisionBoxes, tileTimestamps, tileParseStatus)
  }

  internal actual companion object {
    actual val None: DebugOverlays = DebugOverlays(false, false, false, false)
  }
}
