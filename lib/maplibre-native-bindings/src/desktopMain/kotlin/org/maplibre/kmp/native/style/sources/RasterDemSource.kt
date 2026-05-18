package org.maplibre.kmp.native.style.sources

public class RasterDemSource : Source {
  private val uri: String?
  private val tileSize: Int
  private val tileSetJson: String?

  public constructor(id: String, uri: String, tileSize: Int = 512) : super(id, "raster-dem") {
    this.uri = uri
    this.tileSize = tileSize
    this.tileSetJson = null
  }

  public constructor(
    id: String,
    tiles: List<String>,
    tileSetJson: String?,
  ) : super(id, "raster-dem") {
    this.uri = null
    this.tileSize = 512
    this.tileSetJson = tileSetJson
  }

  override fun configJson(): String? {
    if (uri != null) {
      if (tileSize != 512) return """{"url":${jsonString(uri)},"tileSize":$tileSize}"""
      return jsonString(uri)
    }
    return tileSetJson
  }
}
