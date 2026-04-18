package org.maplibre.compose.sources

import org.maplibre.compose.util.jsonEscape

public actual class RasterSource : Source {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val _sourceId: String
  private val json: String

  public actual constructor(id: String, uri: String, tileSize: Int) {
    _sourceId = id
    json = """{"type":"raster","url":${jsonEscape(uri)},"tileSize":$tileSize}"""
  }

  public actual constructor(id: String, tiles: List<String>, options: TileSetOptions, tileSize: Int) {
    _sourceId = id
    json = buildString {
      append("""{"type":"raster","tiles":[""")
      tiles.joinTo(this, ",") { jsonEscape(it) }
      append("]")
      append(""","tileSize":$tileSize""")
      append(""","minzoom":${options.minZoom}""")
      append(""","maxzoom":${options.maxZoom}""")
      if (options.tileCoordinateSystem == TileCoordinateSystem.TMS) append(""","scheme":"tms"""")
      options.boundingBox?.let { bb ->
        append(""","bounds":[${bb.southwest.longitude},${bb.southwest.latitude},${bb.northeast.longitude},${bb.northeast.latitude}]""")
      }
      options.attributionHtml?.let { append(""","attribution":${jsonEscape(it)}""") }
      append("}")
    }
  }

  override fun toJson(): String = json
}
