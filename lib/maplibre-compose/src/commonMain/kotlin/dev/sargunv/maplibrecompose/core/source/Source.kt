package dev.sargunv.maplibrecompose.core.source

/** A data source for map data */
public expect sealed class Source {
  internal val id: String
  public val attributionHtml: String
}
