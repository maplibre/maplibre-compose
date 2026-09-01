package org.maplibre.compose.offline

import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Geometry

/** Defines a region that an [OfflinePack] stores. */
public sealed interface OfflinePackDefinition {
  public val styleUrl: String

  /**
   * The scale used to resolve `{ratio}` in tile URL templates. MapLibre selects the 2x tile variant
   * for values greater than 1.
   */
  public val pixelRatio: Float

  /** The minimum zoom level for which the pack downloads resources. */
  public val minZoom: Int

  /** The maximum zoom level for which the pack downloads resources, or null for no maximum. */
  public val maxZoom: Int?

  /** Defines an offline region by a style URL, geographic bounds, and zoom range. */
  public data class TilePyramid(
    override val styleUrl: String,
    /** The geographic bounds of the downloaded region. */
    public val bounds: BoundingBox,
    override val pixelRatio: Float,
    override val minZoom: Int = 0,
    override val maxZoom: Int? = null,
  ) : OfflinePackDefinition

  /** Defines an offline region by a style URL, geographic shape, and zoom range. */
  public data class Shape(
    override val styleUrl: String,
    /** The geographic shape of the downloaded region. */
    public val shape: Geometry,
    override val pixelRatio: Float,
    override val minZoom: Int = 0,
    override val maxZoom: Int? = null,
  ) : OfflinePackDefinition
}
