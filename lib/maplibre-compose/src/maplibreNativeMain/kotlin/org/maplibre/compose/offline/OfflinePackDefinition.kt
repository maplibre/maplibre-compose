package org.maplibre.compose.offline

import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Geometry

/** An object that defines a region required by an [OfflinePack]. */
public sealed interface OfflinePackDefinition {
  public val styleUrl: String

  /** The minimum zoom level for which to download tiles and other resources. */
  public val minZoom: Int

  /**
   * The maximum zoom level for which to download tiles and other resources. Null means no maximum.
   */
  public val maxZoom: Int?

  /**
   * The pixel ratio that raster tiles download at. A downloaded raster tile cannot be rescaled, so
   * pass the density of the window that shows the map, such as `LocalDensity.current.density`.
   */
  public val pixelRatio: Float

  /**
   * An offline region defined by a style URL, geographic coordinate bounds, and range of zoom
   * levels.
   *
   * To minimize the resources required by an irregularly shaped offline region, use [Shape]
   * instead.
   */
  public data class TilePyramid(
    override val styleUrl: String,
    /** The coordinate bounds for the geographic region covered by the downloaded tiles. */
    public val bounds: BoundingBox,
    override val minZoom: Int = 0,
    override val maxZoom: Int? = null,
    override val pixelRatio: Float = 1f,
  ) : OfflinePackDefinition

  /** An offline region defined by a style URL, geographic shape, and range of zoom levels. */
  public data class Shape(
    override val styleUrl: String,
    /** The shape for the geographic region covered by the downloaded tiles. */
    public val shape: Geometry,
    override val minZoom: Int = 0,
    override val maxZoom: Int? = null,
    override val pixelRatio: Float = 1f,
  ) : OfflinePackDefinition
}
