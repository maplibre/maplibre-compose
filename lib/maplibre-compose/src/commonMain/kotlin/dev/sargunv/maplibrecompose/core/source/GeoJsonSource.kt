package dev.sargunv.maplibrecompose.core.source

/** A map data source consisting of geojson data. */
public expect class GeoJsonSource : Source {
  /**
   * @param id Unique identifier for this source
   * @param data The GeoJSON data in this source
   * @param options see [GeoJsonOptions]
   */
  public constructor(id: String, data: GeoJsonData, options: GeoJsonOptions)

  public fun setData(data: GeoJsonData)
}
