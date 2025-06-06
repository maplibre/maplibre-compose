package dev.sargunv.maplibrecompose.core.source

public sealed interface GeoJsonData {
  public data class Uri(val uri: String) : GeoJsonData

  public data class JsonString(val json: String) : GeoJsonData

  public data class Features(val geoJson: io.github.dellisd.spatialk.geojson.GeoJson) : GeoJsonData
}
