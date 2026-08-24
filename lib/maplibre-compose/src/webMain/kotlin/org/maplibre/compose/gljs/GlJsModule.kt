@file:JsModule("maplibre-compose/maplibre-gl/index.mjs")

package org.maplibre.compose.gljs

import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsName
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.definedExternally
import web.html.HTMLCanvasElement

internal external fun getVersion(): String

internal external fun getDefaultWorkerUrl(): String

internal external fun setWorkerUrl(value: String)

internal external fun addProtocol(
  customProtocol: String,
  loadFn: (RequestParameters, ProtocolAbortController) -> Promise<ProtocolResponse>,
)

internal external fun removeProtocol(customProtocol: String)

@JsName("Map")
internal external class MaplibreMap(options: MapOptions) : JsAny {

  val _camera: GlJsCamera

  val terrain: GlJsTerrain?

  val painter: Painter

  val style: Style

  var showTileBoundaries: Boolean
  var showCollisionBoxes: Boolean
  var showPadding: Boolean
  var showOverdrawInspector: Boolean

  fun on(type: String, listener: (event: GlJsMapEvent) -> Unit): Subscription

  fun fire(type: String, properties: Any)

  fun getCanvas(): HTMLCanvasElement

  fun setPixelRatio(pixelRatio: Double)

  fun resize()

  fun triggerRepaint()

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

  /** Reloads the loaded or errored tiles of [sourceId] whose canonical IDs are in [tileIds]. */
  fun refreshTiles(sourceId: String, tileIds: Array<CanonicalTileId>)

  fun getRenderWorldCopies(): Boolean

  fun getImage(id: String): GlJsStyleImage?

  fun getPadding(): PaddingOptions

  fun getCenter(): LngLat

  fun getZoom(): Double

  fun getBearing(): Double

  fun getPitch(): Double

  fun cameraForBounds(bounds: LngLatBounds, options: CameraForBoundsOptions): BoundsCamera?

  fun getBounds(): LngLatBounds

  fun getMaxBounds(): LngLatBounds?

  fun getMinZoom(): Double

  fun getMaxZoom(): Double

  fun getMinPitch(): Double

  fun getMaxPitch(): Double

  fun jumpTo(options: JumpToOptions)

  fun easeTo(options: EaseToOptions)

  fun flyTo(options: FlyToOptions)

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

