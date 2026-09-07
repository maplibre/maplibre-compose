@file:JsModule("maplibre-gl")

package org.maplibre.compose.gljs

import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsName
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.definedExternally
import web.html.HTMLCanvasElement

internal external fun getVersion(): String

internal external fun setWorkerUrl(value: String)

internal external fun addProtocol(
  customProtocol: String,
  loadFn: (RequestParameters, ProtocolAbortController) -> Promise<ProtocolResponse>,
)

internal external fun removeProtocol(customProtocol: String)

@JsName("Map")
internal external class MaplibreMap(options: MapOptions) : JsAny {

  val painter: Painter

  val style: Style

  var showTileBoundaries: Boolean
  var showCollisionBoxes: Boolean
  var showPadding: Boolean
  var showOverdrawInspector: Boolean

  fun on(type: String, listener: (event: GlJsMapEvent) -> Unit): Subscription

  fun fire(type: String, properties: JsAny)

  fun getCanvas(): HTMLCanvasElement

  fun setPixelRatio(pixelRatio: Double)

  fun resize()

  fun redraw()

  fun remove()

  fun setStyle(style: StyleSource, options: SetStyleOptions)

  fun getStyle(): StyleSpecification

  fun setLight(light: LightSpecification, options: StyleSetterOptions = definedExternally)

  fun getLight(): LightSpecification

  fun setSky(sky: SkySpecification?, options: StyleSetterOptions = definedExternally)

  fun getSky(): SkySpecification?

  fun setProjection(projection: ProjectionSpecification?)

  fun getProjection(): ProjectionSpecification?

  fun isStyleLoaded(): Boolean

  fun isSourceLoaded(id: String): Boolean

  fun getCenter(): LngLat

  fun getZoom(): Double

  fun getBearing(): Double

  fun getPitch(): Double

  fun getBounds(): LngLatBounds

  fun getMaxBounds(): LngLatBounds?

  fun getMinZoom(): Double

  fun getMaxZoom(): Double

  fun getMinPitch(): Double

  fun getMaxPitch(): Double

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
  ): JsArray<MapGeoJsonFeature>

  fun querySourceFeatures(
    sourceId: String,
    options: QuerySourceFeatureOptions,
  ): JsArray<GeoJsonFeature>

  fun setFeatureState(feature: FeatureIdentifier, state: JsAny)

  fun getFeatureState(feature: FeatureIdentifier): JsAny?

  fun removeFeatureState(feature: FeatureIdentifier, key: String = definedExternally)

  fun addSource(id: String, source: SourceSpecification)

  fun removeSource(id: String)

  /** Unbounded: MapLibre bounds this by its own `Source`, which collides with this library's. */
  fun <T : JsAny> getSource(id: String): T?

  fun addLayer(layer: LayerSpecification, beforeId: String = definedExternally)

  fun moveLayer(id: String, beforeId: String = definedExternally)

  fun removeLayer(id: String)

  fun getLayer(id: String): StyleLayer?

  fun getLayersOrder(): JsArray<JsString>

  fun setLayerZoomRange(layerId: String, minzoom: Double, maxzoom: Double)

  fun setFilter(layerId: String, filter: FilterSpecification?)

  fun getFilter(layerId: String): FilterSpecification?

  fun setPaintProperty(layerId: String, name: String, value: JsAny?)

  fun getPaintProperty(layerId: String, name: String): JsAny?

  fun setLayoutProperty(layerId: String, name: String, value: JsAny?)

  fun getLayoutProperty(layerId: String, name: String): JsAny?

  fun addImage(id: String, image: StyleImageData, options: StyleImageMetadata)

  fun hasImage(id: String): Boolean

  fun removeImage(id: String)

  /**
   * MapLibre awaits the promise that the resolver returns before it treats the image as missing. A
   * null [resolver] removes the one in place; MapLibre exposes no separate remover.
   */
  fun setMissingStyleImageResolver(resolver: ((id: String) -> Promise<*>?)?)
}

internal external class LngLat(lng: Double, lat: Double) : JsAny {
  val lng: Double
  val lat: Double
}

internal external class LngLatBounds(sw: LngLat, ne: LngLat) : JsAny {
  fun getSouthWest(): LngLat

  fun getNorthEast(): LngLat
}

/**
 * MapLibre exposes no transition setter, and its style diff treats `setTransition` as a no-op, so
 * update [stylesheet], the public field that [getTransition] reads every frame.
 */
internal external class Style {
  var stylesheet: StyleSpecification

  val light: Light

  val sky: Sky

  fun getTransition(): TransitionSpecification
}

/**
 * The style's light validates its input and reports a rejected write as an `error` event on itself,
 * with no evented parent, so a listener on the map never hears it.
 */
internal external class Light {
  fun on(type: String, listener: (event: GlJsMapEvent) -> Unit): Subscription
}

/** The style's sky reports a rejected write the same way as [Light]. */
internal external class Sky {
  fun on(type: String, listener: (event: GlJsMapEvent) -> Unit): Subscription
}
