package org.maplibre.kmp.js.stylespec.sources

import kotlin.js.Json
import kotlin.js.json
import org.maplibre.kmp.js.stylespec.Expression
import org.maplibre.kmp.js.util.jso

public fun GeoJSONSourceSpecification(
  data: GeoJsonDataDefinition,
  cluster: Boolean? = null,
  clusterRadius: Int? = null,
  clusterMaxZoom: Int? = null,
  clusterMinPoints: Int? = null,
  maxzoom: Int? = null,
  attribution: String? = null,
  buffer: Int? = null,
  filter: Expression? = null,
  tolerance: Double? = null,
  clusterProperties: Json? = null,
  lineMetrics: Boolean? = null,
  generateId: Boolean? = null,
  promoteId: PromoteIdDefinition? = null,
): GeoJSONSourceSpecification = jso {
  this.type = "geojson"
  this.data = data
  cluster?.let { this.cluster = it }
  clusterRadius?.let { this.clusterRadius = it }
  clusterMaxZoom?.let { this.clusterMaxZoom = it }
  clusterMinPoints?.let { this.clusterMinPoints = it }
  maxzoom?.let { this.maxzoom = it }
  attribution?.let { this.attribution = it }
  buffer?.let { this.buffer = it }
  filter?.let { this.filter = it }
  tolerance?.let { this.tolerance = it }
  clusterProperties?.let { this.clusterProperties = it }
  lineMetrics?.let { this.lineMetrics = it }
  generateId?.let { this.generateId = it }
  promoteId?.let { this.promoteId = it }
}

public fun VectorSourceSpecification(
  url: String? = null,
  tiles: Array<String>? = null,
  bounds: Array<Double>? = null,
  scheme: String? = null,
  minzoom: Int? = null,
  maxzoom: Int? = null,
  attribution: String? = null,
  promoteId: PromoteIdDefinition? = null,
  volatile: Boolean? = null,
  encoding: String? = null,
): VectorSourceSpecification = jso {
  this.type = "vector"
  url?.let { this.url = it }
  tiles?.let { this.tiles = it }
  bounds?.let { this.bounds = it }
  scheme?.let { this.scheme = it }
  minzoom?.let { this.minzoom = it }
  maxzoom?.let { this.maxzoom = it }
  attribution?.let { this.attribution = it }
  promoteId?.let { this.promoteId = it }
  volatile?.let { this.volatile = it }
  encoding?.let { this.encoding = it }
}

public fun RasterSourceSpecification(
  url: String? = null,
  tiles: Array<String>? = null,
  bounds: Array<Double>? = null,
  minzoom: Int? = null,
  maxzoom: Int? = null,
  tileSize: Int? = null,
  scheme: String? = null,
  attribution: String? = null,
  volatile: Boolean? = null,
): RasterSourceSpecification = jso {
  this.type = "raster"
  url?.let { this.url = it }
  tiles?.let { this.tiles = it }
  bounds?.let { this.bounds = it }
  minzoom?.let { this.minzoom = it }
  maxzoom?.let { this.maxzoom = it }
  tileSize?.let { this.tileSize = it }
  scheme?.let { this.scheme = it }
  attribution?.let { this.attribution = it }
  volatile?.let { this.volatile = it }
}

public fun RasterDEMSourceSpecification(
  url: String? = null,
  tiles: Array<String>? = null,
  bounds: Array<Double>? = null,
  minzoom: Int? = null,
  maxzoom: Int? = null,
  tileSize: Int? = null,
  attribution: String? = null,
  encoding: String? = null,
  redFactor: Double? = null,
  blueFactor: Double? = null,
  greenFactor: Double? = null,
  baseShift: Double? = null,
): RasterDEMSourceSpecification = jso {
  this.type = "raster-dem"
  url?.let { this.url = it }
  tiles?.let { this.tiles = it }
  bounds?.let { this.bounds = it }
  minzoom?.let { this.minzoom = it }
  maxzoom?.let { this.maxzoom = it }
  tileSize?.let { this.tileSize = it }
  attribution?.let { this.attribution = it }
  encoding?.let { this.encoding = it }
  redFactor?.let { this.redFactor = it }
  blueFactor?.let { this.blueFactor = it }
  greenFactor?.let { this.greenFactor = it }
  baseShift?.let { this.baseShift = it }
}

public fun ImageSourceSpecification(
  url: String,
  coordinates: Array<Array<Double>>,
): ImageSourceSpecification = jso {
  this.type = "image"
  this.url = url
  this.coordinates = coordinates
}

public fun VideoSourceSpecification(
  urls: Array<String>,
  coordinates: Array<Array<Double>>,
): VideoSourceSpecification = jso {
  this.type = "video"
  this.urls = urls
  this.coordinates = coordinates
}

public fun GeoJsonDataDefinition(url: String): GeoJsonDataDefinition =
  url.unsafeCast<GeoJsonDataDefinition>()

public fun GeoJsonDataDefinition(geojson: Json): GeoJsonDataDefinition =
  geojson.unsafeCast<GeoJsonDataDefinition>()

public fun PromoteIdDefinition(property: String): PromoteIdDefinition =
  property.unsafeCast<PromoteIdDefinition>()

public fun PromoteIdDefinition(layerToProperty: Map<String, String>): PromoteIdDefinition =
  json()
    .let { ret -> layerToProperty.forEach { (key, value) -> ret[key] = value } }
    .unsafeCast<PromoteIdDefinition>()
