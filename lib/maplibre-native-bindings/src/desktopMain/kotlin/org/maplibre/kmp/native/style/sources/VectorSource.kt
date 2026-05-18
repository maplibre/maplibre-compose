package org.maplibre.kmp.native.style.sources

public class VectorSource : Source {
  private val uri: String?
  private val tiles: List<String>?
  private val tileSetJson: String?

  public constructor(id: String, uri: String) : super(id, "vector") {
    this.uri = uri
    this.tiles = null
    this.tileSetJson = null
  }

  public constructor(id: String, tiles: List<String>, tileSetJson: String?) : super(id, "vector") {
    this.uri = null
    this.tiles = tiles
    this.tileSetJson = tileSetJson
  }

  override fun configJson(): String? {
    if (uri != null) return jsonString(uri)
    if (tiles != null) return tileSetJson
    return null
  }

  public fun querySourceFeatures(sourceLayersJson: String?, filterJson: String?): String {
    val m = map ?: return """{"type":"FeatureCollection","features":[]}"""
    return m.querySourceFeatures(id, sourceLayersJson, filterJson)
  }
}
