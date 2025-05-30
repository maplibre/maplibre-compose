package dev.sargunv.maplibrecompose.compose.offline

import io.github.dellisd.spatialk.geojson.BoundingBox
import io.github.dellisd.spatialk.geojson.Geometry

public sealed interface TilePackDefinition {
  public val styleUrl: String
  public val minZoom: Int
  public val maxZoom: Int?

  public data class TilePyramid(
    override val styleUrl: String,
    public val bounds: BoundingBox,
    override val minZoom: Int = 0,
    override val maxZoom: Int? = null,
  ) : TilePackDefinition

  public data class Shape(
    override val styleUrl: String,
    public val geometry: Geometry,
    override val minZoom: Int = 0,
    override val maxZoom: Int? = null,
  ) : TilePackDefinition
}
