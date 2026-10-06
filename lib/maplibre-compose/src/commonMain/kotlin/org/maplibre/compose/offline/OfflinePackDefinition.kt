package org.maplibre.compose.offline

import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Geometry

/**
 * Defines a region that an [OfflinePack] stores.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface OfflinePackDefinition {
  public val styleUrl: String

  /**
   * The ratio of physical pixels to density-independent pixels that the pack's resources are
   * downloaded for, usually the screen's `Density.density`. MapLibre uses it to resolve `{ratio}`
   * in tile URL templates, selecting the 2x tile variant for values greater than 1. Packs always
   * include both the 1x and 2x sprites.
   *
   * MapLibre Native stores this value with single precision, so a pack read back from the database
   * can report a value that differs from the one it was created with in the last decimal places.
   */
  public val pixelRatio: Double

  /**
   * The minimum camera zoom for which the pack downloads resources. MapLibre converts camera zoom
   * to tile zoom using each source's tile size, flooring vector zoom and rounding raster zoom.
   */
  public val minZoom: Double

  /** The maximum camera zoom to download, converted as for [minZoom], or null for no maximum. */
  public val maxZoom: Double?

  /** Defines an offline region by a style URL, geographic bounds, and zoom range. */
  public data class TilePyramid(
    override val styleUrl: String,
    /** The geographic bounds of the downloaded region. */
    public val bounds: BoundingBox,
    override val pixelRatio: Double,
    override val minZoom: Double = 0.0,
    override val maxZoom: Double? = null,
  ) : OfflinePackDefinition

  /** Defines an offline region by a style URL, geographic shape, and zoom range. */
  public data class Shape(
    override val styleUrl: String,
    /** The geographic shape of the downloaded region. */
    public val shape: Geometry,
    override val pixelRatio: Double,
    override val minZoom: Double = 0.0,
    override val maxZoom: Double? = null,
  ) : OfflinePackDefinition
}

/**
 * Keeps [OfflinePackDefinition] open: callers' `when` needs an `else` branch. It has no instances.
 */
internal abstract class UnspecifiedOfflinePackDefinition private constructor() :
  OfflinePackDefinition
