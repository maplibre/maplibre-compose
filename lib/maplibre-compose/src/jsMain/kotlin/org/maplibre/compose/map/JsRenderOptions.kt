package org.maplibre.compose.map

import androidx.compose.runtime.Immutable
import org.maplibre.compose.map.internal.commonEquals
import org.maplibre.compose.map.internal.commonHashCode
import org.maplibre.compose.map.internal.commonToString
import org.maplibre.compose.map.internal.validate

@Immutable
public actual class RenderOptions
private constructor(
  public actual val maximumFps: Int?,
  public actual val tileLod: TileLodOptions,
  public actual val debug: DebugOverlays,
) {
  public actual constructor(
    from: RenderOptions,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(builder.maximumFps, builder.tileLod, builder.debugBuilder.build())

  init {
    validate()
  }

  actual override fun equals(other: Any?): Boolean = other is RenderOptions && commonEquals(other)

  actual override fun hashCode(): Int = commonHashCode(0)

  actual override fun toString(): String = commonToString()

  @MapOptionsDsl
  public actual class Builder internal actual constructor(from: RenderOptions) {
    public actual var maximumFps: Int? = from.maximumFps
    public actual var tileLod: TileLodOptions = from.tileLod
    internal val debugBuilder = DebugOverlays.Builder(from.debug)

    public actual fun debug(block: DebugOverlays.Builder.() -> Unit) {
      debugBuilder.apply(block)
    }
  }

  public actual companion object {
    public actual val Standard: RenderOptions =
      RenderOptions(null, TileLodOptions.Standard, DebugOverlays.None)

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
public actual class DebugOverlays
private constructor(
  public actual val tileBorders: Boolean,
  public actual val collisionBoxes: Boolean,
  /** Draws the camera's padding, which is where it considers its center to be. */
  public val padding: Boolean,
  /** Shades the map by how many times each pixel was drawn. */
  public val overdrawInspector: Boolean,
) {
  actual override fun equals(other: Any?): Boolean =
    other is DebugOverlays &&
      commonEquals(other) &&
      padding == other.padding &&
      overdrawInspector == other.overdrawInspector

  actual override fun hashCode(): Int =
    commonHashCode(31 * padding.hashCode() + overdrawInspector.hashCode())

  actual override fun toString(): String =
    commonToString("padding" to padding, "overdrawInspector" to overdrawInspector)

  @MapOptionsDsl
  public actual class Builder internal actual constructor(from: DebugOverlays) {
    public actual var tileBorders: Boolean = from.tileBorders
    public actual var collisionBoxes: Boolean = from.collisionBoxes
    /** See [DebugOverlays.padding]. */
    public var padding: Boolean = from.padding
    /** See [DebugOverlays.overdrawInspector]. */
    public var overdrawInspector: Boolean = from.overdrawInspector

    internal actual fun build(): DebugOverlays =
      DebugOverlays(tileBorders, collisionBoxes, padding, overdrawInspector)
  }

  internal actual companion object {
    actual val None: DebugOverlays = DebugOverlays(false, false, false, false)
  }
}
