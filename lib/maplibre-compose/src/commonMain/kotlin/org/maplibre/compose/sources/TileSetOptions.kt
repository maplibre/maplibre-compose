package org.maplibre.compose.sources

import androidx.compose.runtime.Immutable
import org.maplibre.compose.map.MapOptionsDsl
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * The TileJSON fields of a tiled source.
 *
 * @property minZoom Minimum zoom level for which tiles are available. Defaults to 0.
 * @property maxZoom Maximum zoom level for which tiles are available. MapLibre overzooms the
 *   highest tiles beyond it. Defaults to 22, the style spec default for vector, raster, and
 *   raster-dem sources.
 * @property scheme How the tile URLs number tile rows. Defaults to [TileScheme.Xyz].
 * @property boundingBox The area that has tiles. MapLibre requests no tiles outside it. Null means
 *   the whole world.
 * @property attributionHtml Attribution shown for this source, as HTML.
 */
@Immutable
public data class TileSetOptions
internal constructor(
  public val minZoom: Int,
  public val maxZoom: Int,
  public val scheme: TileScheme,
  public val boundingBox: BoundingBox?,
  public val attributionHtml: String?,
) {

  /** Edits [from]; omitted settings inherit. */
  public constructor(
    from: TileSetOptions = Standard,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(
    builder: Builder
  ) : this(
    builder.minZoom,
    builder.maxZoom,
    builder.scheme,
    builder.boundingBox,
    builder.attributionHtml,
  )

  @MapOptionsDsl
  public class Builder internal constructor(from: TileSetOptions) {
    /** See [TileSetOptions.minZoom]. */
    public var minZoom: Int = from.minZoom
    /** See [TileSetOptions.maxZoom]. */
    public var maxZoom: Int = from.maxZoom
    /** See [TileSetOptions.scheme]. */
    public var scheme: TileScheme = from.scheme
    /** See [TileSetOptions.boundingBox]. */
    public var boundingBox: BoundingBox? = from.boundingBox
    /** See [TileSetOptions.attributionHtml]. */
    public var attributionHtml: String? = from.attributionHtml
  }

  public companion object {
    /** The default source settings. */
    public val Standard: TileSetOptions = TileSetOptions(0, 22, TileScheme.Xyz, null, null)
  }
}
