@file:JsModule("maplibre-gl")

package org.maplibre.compose.gljs

import kotlin.js.Promise
import web.html.HTMLCanvasElement

internal external fun getVersion(): String

internal external fun setWorkerUrl(value: String)

internal external fun addProtocol(
  customProtocol: String,
  loadFn: (RequestParameters, Any) -> Promise<ProtocolResponse>,
)

internal external fun removeProtocol(customProtocol: String)

@JsName("Map")
public external class MaplibreMap(options: MapOptions) {

  public val painter: Painter

  public var showTileBoundaries: Boolean
  public var showCollisionBoxes: Boolean
  public var showPadding: Boolean
  public var showOverdrawInspector: Boolean

  public fun on(type: String, listener: (event: MapEvent) -> Unit): Subscription

  public fun getCanvas(): HTMLCanvasElement

  public fun setPixelRatio(pixelRatio: Double)

  public fun resize()

  public fun redraw()

  public fun remove()

  public fun setStyle(style: StyleSource, options: SetStyleOptions)

  public fun getStyle(): StyleSpecification

  public fun isStyleLoaded(): Boolean

  public fun isSourceLoaded(id: String): Boolean

  public fun getCenter(): LngLat

  public fun getZoom(): Double

  public fun getBearing(): Double

  public fun getPitch(): Double

  public fun getBounds(): LngLatBounds

  public fun jumpTo(options: JumpToOptions)

  public fun easeTo(options: EaseToOptions)

  public fun flyTo(options: FlyToOptions)

  public fun cameraForBounds(
    bounds: LngLatBounds,
    options: CameraForBoundsOptions,
  ): CenterZoomBearing?

  public fun panBy(offset: Point, options: EaseToOptions)

  public fun stop()

  public fun setMaxBounds(bounds: LngLatBounds?)

  public fun setMinZoom(minZoom: Double)

  public fun setMaxZoom(maxZoom: Double)

  public fun setMinPitch(minPitch: Double)

  public fun setMaxPitch(maxPitch: Double)

  public fun setSourceTileLodParams(maxZoomLevelsOnScreen: Double, tileCountMaxMinRatio: Double)

  public fun project(lngLat: LngLat): Point

  public fun unproject(point: Point): LngLat

  public fun queryRenderedFeatures(
    geometry: QueryGeometry,
    options: QueryRenderedFeaturesOptions,
  ): Array<MapGeoJsonFeature>

  public fun querySourceFeatures(
    sourceId: String,
    options: QuerySourceFeatureOptions,
  ): Array<GeoJsonFeature>

  public fun setFeatureState(feature: FeatureIdentifier, state: Any)

  public fun getFeatureState(feature: FeatureIdentifier): Any?

  public fun removeFeatureState(feature: FeatureIdentifier, key: String = definedExternally)

  public fun addSource(id: String, source: SourceSpecification)

  public fun removeSource(id: String)

  /** Unbounded: MapLibre bounds this by its own `Source`, which collides with this library's. */
  public fun <T : Any> getSource(id: String): T?

  public fun addLayer(layer: LayerSpecification, beforeId: String = definedExternally)

  public fun moveLayer(id: String, beforeId: String = definedExternally)

  public fun removeLayer(id: String)

  public fun getLayer(id: String): StyleLayer?

  public fun getLayersOrder(): Array<String>

  public fun setLayerZoomRange(layerId: String, minzoom: Double, maxzoom: Double)

  public fun setFilter(layerId: String, filter: FilterSpecification?)

  public fun setPaintProperty(layerId: String, name: String, value: Any?)

  public fun getPaintProperty(layerId: String, name: String): Any?

  public fun setLayoutProperty(layerId: String, name: String, value: Any?)

  public fun getLayoutProperty(layerId: String, name: String): Any?

  public fun addImage(id: String, image: StyleImageData, options: StyleImageMetadata)

  public fun hasImage(id: String): Boolean

  public fun removeImage(id: String)
}

public external class LngLat(lng: Double, lat: Double) {
  public val lng: Double
  public val lat: Double
}

public external class LngLatBounds(sw: LngLat, ne: LngLat) {
  public fun getSouthWest(): LngLat

  public fun getNorthEast(): LngLat
}
