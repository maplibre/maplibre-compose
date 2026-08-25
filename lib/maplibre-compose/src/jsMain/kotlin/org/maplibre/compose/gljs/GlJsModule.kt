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
internal external class MaplibreMap(options: MapOptions) {

  val painter: Painter

  var showTileBoundaries: Boolean
  var showCollisionBoxes: Boolean
  var showPadding: Boolean
  var showOverdrawInspector: Boolean

  fun on(type: String, listener: (event: MapEvent) -> Unit): Subscription

  fun getCanvas(): HTMLCanvasElement

  fun setPixelRatio(pixelRatio: Double)

  fun resize()

  fun redraw()

  fun remove()

  fun setStyle(style: StyleSource, options: SetStyleOptions)

  fun getStyle(): StyleSpecification

  fun isStyleLoaded(): Boolean

  fun isSourceLoaded(id: String): Boolean

  fun getCenter(): LngLat

  fun getZoom(): Double

  fun getBearing(): Double

  fun getPitch(): Double

  fun getBounds(): LngLatBounds

  fun jumpTo(options: JumpToOptions)

  fun easeTo(options: EaseToOptions)

  fun flyTo(options: FlyToOptions)

  fun cameraForBounds(bounds: LngLatBounds, options: CameraForBoundsOptions): CenterZoomBearing?

  fun panBy(offset: Point, options: EaseToOptions)

  fun stop()

  fun setMaxBounds(bounds: LngLatBounds?)

  fun setMinZoom(minZoom: Double)

  fun setMaxZoom(maxZoom: Double)

  fun setMinPitch(minPitch: Double)

  fun setMaxPitch(maxPitch: Double)

  fun setSourceTileLodParams(maxZoomLevelsOnScreen: Double, tileCountMaxMinRatio: Double)

  fun project(lngLat: LngLat): Point

  fun unproject(point: Point): LngLat

  fun queryRenderedFeatures(
    geometry: QueryGeometry,
    options: QueryRenderedFeaturesOptions,
  ): Array<MapGeoJsonFeature>

  fun querySourceFeatures(
    sourceId: String,
    options: QuerySourceFeatureOptions,
  ): Array<GeoJsonFeature>

  fun setFeatureState(feature: FeatureIdentifier, state: Any)

  fun getFeatureState(feature: FeatureIdentifier): Any?

  fun removeFeatureState(feature: FeatureIdentifier, key: String = definedExternally)

  fun addSource(id: String, source: SourceSpecification)

  fun removeSource(id: String)

  /** Unbounded: MapLibre bounds this by its own `Source`, which collides with this library's. */
  fun <T : Any> getSource(id: String): T?

  fun addLayer(layer: LayerSpecification, beforeId: String = definedExternally)

  fun moveLayer(id: String, beforeId: String = definedExternally)

  fun removeLayer(id: String)

  fun getLayer(id: String): StyleLayer?

  fun getLayersOrder(): Array<String>

  fun setLayerZoomRange(layerId: String, minzoom: Double, maxzoom: Double)

  fun setFilter(layerId: String, filter: FilterSpecification?)

  fun setPaintProperty(layerId: String, name: String, value: Any?)

  fun getPaintProperty(layerId: String, name: String): Any?

  fun setLayoutProperty(layerId: String, name: String, value: Any?)

  fun getLayoutProperty(layerId: String, name: String): Any?

  fun addImage(id: String, image: StyleImageData, options: StyleImageMetadata)

  fun hasImage(id: String): Boolean

  fun removeImage(id: String)
}

internal external class LngLat(lng: Double, lat: Double) {
  val lng: Double
  val lat: Double
}

internal external class LngLatBounds(sw: LngLat, ne: LngLat) {
  fun getSouthWest(): LngLat

  fun getNorthEast(): LngLat
}
