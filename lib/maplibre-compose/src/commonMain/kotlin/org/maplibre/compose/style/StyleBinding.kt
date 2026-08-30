package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.CustomGeometrySourceOptions
import org.maplibre.compose.sources.CustomVectorSourceOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.TileCoordinate
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.compose.sources.putGeoJsonOptions
import org.maplibre.compose.sources.rasterDemSourceJson
import org.maplibre.compose.sources.toDataJson
import org.maplibre.compose.util.ImageStretch
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/**
 * The engine binding for one loaded style, identified by one opaque generation. The binding stops
 * working when its style unloads. Operations on an invalidated binding fail with a stale-style
 * error.
 */
internal interface StyleBinding {
  /** The loaded base-style generation associated with every operation on this binding. */
  val identity: StyleIdentity

  val isLoaded: Boolean

  /** Invalidates this loaded style before its base style starts changing. */
  fun invalidate()

  fun requireCurrent() {
    check(isLoaded) {
      "Style operation belongs to a stale loaded-style identity"
    }
  }

  val logger: Logger?

  fun addImage(definition: StyleImageDefinition)

  fun addImage(id: String, image: ImageBitmap, sdf: Boolean, stretch: ImageStretch?) {
    addImage(StyleImageDefinition(id, ImageSnapshot.capture(image), sdf, stretch))
  }

  fun removeImage(id: String)

  fun getSource(id: String): Source?

  fun getSources(): List<Source>

  fun getLayer(id: String): Layer?

  fun getLayers(): List<Layer>

  fun layerIds(): List<String>

  /**
   * Adds a complete layer object directly below [beforeLayerId], or on top when that is empty.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if MapLibre refuses the layer.
   */
  fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean

  fun addLayer(definition: LayerDefinition, beforeLayerId: String): Boolean {
    requireCurrent()
    return addLayer(definition.value, beforeLayerId)
  }

  fun removeLayer(layerId: String)

  /** Moves a layer to sit directly below [beforeLayerId], or on top when that is empty. */
  fun moveLayer(layerId: String, beforeLayerId: String)

  /**
   * Sets one property on a layer that is already in the style.
   *
   * @param kind which of a layer object's three parts [name] belongs to; a name offered to the
   *   wrong one is rejected.
   * @throws StyleMutationException if MapLibre refuses [value]; the layer keeps its previous one.
   */
  fun setLayerProperty(layerId: String, name: String, value: JsonElement, kind: LayerPropertyKind)

  fun setLayerFilter(layerId: String, filter: JsonElement)

  /** @return null if the style has unloaded, or the layer holds no value for [name]. */
  fun layerProperty(layerId: String, name: String): JsonElement?

  /**
   * Reports whether a live layer with [layerId] is in the style, so a duplicate can be refused on
   * the caller with the message that names the cause.
   *
   * @return null if the style has unloaded or the answer could not be determined; the add still
   *   refuses a duplicate in that case.
   */
  fun layerExists(layerId: String): Boolean?

  /**
   * Why this engine cannot take a layer property, or null when it can. The style spec is shared,
   * but each engine implements it at its own pace; a non-null reason keeps the property out of
   * every write to this binding, and the layer reports it once instead.
   */
  fun unsupportedLayerPropertyReason(layerType: String, name: String): String? = null

  /**
   * Whether this engine decodes a raster-dem source's custom encoding factors; an engine that does
   * not takes the Mapbox encoding instead.
   */
  val supportsCustomDemEncoding: Boolean

  /** Whether this engine takes a `scheme` on a raster-dem source; the style spec has none. */
  val supportsRasterDemScheme: Boolean

  /**
   * Adds a source from its style-spec definition.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if MapLibre refuses the source.
   */
  fun addSource(sourceId: String, source: JsonObject): Boolean

  /** Installs one immutable source definition in this loaded style. */
  fun addSource(definition: SourceDefinition): Boolean {
    requireCurrent()
    return when (definition) {
      is SourceDefinition.Json -> addSource(definition.id, definition.value)
      is SourceDefinition.GeoJson ->
        addGeoJsonSource(definition.id, definition.data, definition.options)
      is SourceDefinition.Image ->
        definition.image?.let {
          addImageSourceImage(definition.id, definition.coordinates, it.toImageBitmap())
        } ?: addSource(definition.id, definition.value)
      is SourceDefinition.CustomGeometry ->
        addCustomGeometrySource(definition.id, definition.options, definition.provider)
      is SourceDefinition.CustomVector ->
        addCustomVectorSource(definition.id, definition.options, definition.provider)
      is SourceDefinition.RasterDem ->
        addSource(
          definition.id,
          rasterDemSourceJson(
            tiles = definition.tiles,
            options = definition.options,
            tileSize = definition.tileSize,
            demEncoding = definition.demEncoding,
            capabilities =
              RasterDemCapabilities(supportsCustomDemEncoding, supportsRasterDemScheme),
          ),
        )
    }
  }

  /** Removes a source and forgets the feature state that belonged to it. */
  fun removeSource(sourceId: String)

  /**
   * Reports whether a live source with [sourceId] is in the style, so a duplicate can be refused on
   * the caller with the message that names the cause.
   *
   * @return null if the style has unloaded or the answer could not be determined; the add still
   *   refuses a duplicate in that case.
   */
  fun sourceExists(sourceId: String): Boolean?

  /**
   * Adds an image source carrying its pixels, for engines whose source JSON can only name a URL.
   *
   * @param coordinates the four corners in MapLibre's order: top left, top right, bottom right,
   *   bottom left.
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if MapLibre refuses the source.
   */
  fun addImageSourceImage(
    sourceId: String,
    coordinates: List<Position>,
    image: ImageBitmap,
  ): Boolean

  /** Replaces an image source's content with a bitmap. */
  fun setImageSourceImage(sourceId: String, image: ImageBitmap)

  /** Replaces an image source's content with a URL. */
  fun setImageSourceUrl(sourceId: String, url: String)

  /** Moves an image source's four corners, given in MapLibre's order. */
  fun setImageSourceCoordinates(sourceId: String, coordinates: List<Position>)

  /** @return null if the style has unloaded, or the source is not a live image source. */
  fun imageSourceCoordinates(sourceId: String): List<Position>?

  /**
   * Adds a GeoJSON source from its data and options. The default writes the style-spec JSON; an
   * engine with a typed adder overrides it.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if MapLibre refuses the source or the data.
   */
  fun addGeoJsonSource(sourceId: String, data: GeoJsonData, options: GeoJsonOptions): Boolean =
    addSource(
      sourceId,
      buildJsonObject {
        put("type", "geojson")
        put("data", data.toDataJson())
        putGeoJsonOptions(options)
      },
    )

  /**
   * Converts inline [data] into this engine's install form on the caller, so an expensive parse
   * stays off the map's owner thread. A URL installs through [setGeoJsonSourceUrl] instead.
   */
  fun prepareGeoJson(data: GeoJsonData, options: GeoJsonOptions): PreparedGeoJson

  /**
   * Installs [prepared] on a live GeoJSON source when [claim] answers true.
   *
   * [claim] runs where this engine serializes installs, so overlapping installs resolve their order
   * in one place. It runs even when the style has unloaded or the install is dropped, so the live
   * handle records the applied data. Returns after the install has run or been dropped, so the
   * caller may close [prepared].
   */
  fun setGeoJsonSourceData(sourceId: String, prepared: PreparedGeoJson, claim: () -> Boolean)

  /** Points a live GeoJSON source at [url]; [claim] follows the [setGeoJsonSourceData] contract. */
  fun setGeoJsonSourceUrl(sourceId: String, url: String, claim: () -> Boolean)

  /**
   * Adds a custom geometry source whose feature tiles [provider] supplies. The engine owns the
   * tile-serving machinery for the source and tears it down on remove or unload.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws UnsupportedOperationException on an engine with no custom geometry source (MapLibre GL
   *   JS).
   * @throws StyleMutationException if MapLibre refuses the source.
   */
  fun addCustomGeometrySource(
    sourceId: String,
    options: CustomGeometrySourceOptions,
    provider: GeometryTileProvider,
  ): Boolean

  /** Requests new features for a custom geometry source's tiles that intersect [bounds]. */
  fun invalidateCustomGeometrySourceBounds(sourceId: String, bounds: BoundingBox)

  /** Requests new features for one tile of a custom geometry source when MapLibre needs it. */
  fun invalidateCustomGeometrySourceTile(sourceId: String, tile: TileCoordinate)

  /**
   * Adds a custom vector source whose MVT tiles [provider] supplies. The engine owns the
   * tile-serving machinery for the source and tears it down on remove or unload.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if MapLibre refuses the source.
   */
  fun addCustomVectorSource(
    sourceId: String,
    options: CustomVectorSourceOptions,
    provider: VectorTileProvider,
  ): Boolean

  /**
   * Requests new data for one tile of a custom vector source when MapLibre needs it.
   *
   * @throws UnsupportedOperationException on MapLibre GL JS, which exposes no public per-tile
   *   invalidation operation.
   */
  fun invalidateCustomVectorSourceTile(sourceId: String, tile: TileCoordinate)

  /**
   * The zoom at which [feature]'s cluster breaks apart, or null when the feature carries no cluster
   * id, no live source can answer, or the cluster is gone.
   */
  suspend fun clusterExpansionZoom(sourceId: String, feature: Feature<*, JsonObject?>): Double?

  /** The features one level down from [feature]'s cluster; null as [clusterExpansionZoom]. */
  suspend fun clusterChildren(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): FeatureCollection<Geometry, JsonObject?>?

  /** The original points under [feature]'s cluster; null as [clusterExpansionZoom]. */
  suspend fun clusterLeaves(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<Geometry, JsonObject?>?

  /**
   * Reports that [sourceId] was added or removed, so the style state can refresh that source
   * without waiting for idle. An engine that adds sources through its own hop calls this from
   * inside that hop, after the add or remove.
   */
  fun reportSourceChanged(sourceId: String) {}

  /** Merges [state] into the state of one feature; a null value in [state] drops that key. */
  fun setFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    state: JsonObject,
  )

  /** @return an empty object when the feature has no state, or the style has unloaded. */
  fun featureState(sourceId: String, sourceLayerId: String?, featureId: String): JsonObject

  /** Removes one key of a feature's state, or the whole state when [stateKey] is null. */
  fun removeFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  )

  /** Removes the state of every feature in a source, or in one of its source layers. */
  fun resetFeatureStates(sourceId: String, sourceLayerId: String?)

  /**
   * Queries the features a source has loaded, whether or not they are drawn.
   *
   * @param filter a style-spec filter expression, or null to match every feature.
   * @return empty when the style has unloaded or nothing has rendered yet.
   */
  fun querySourceFeatures(
    sourceId: String,
    sourceLayerIds: Set<String>,
    filter: JsonElement?,
  ): List<Feature<Geometry, JsonObject?>>
}

/** An engine's install form of one GeoJSON payload. [close] releases what the engine holds. */
internal interface PreparedGeoJson : AutoCloseable

/** The prepared form of an engine with nothing to prepare. */
internal object NoPreparedGeoJson : PreparedGeoJson {
  override fun close() = Unit
}

/** Which part of a layer object a property belongs to. */
internal enum class LayerPropertyKind {
  LAYOUT,
  PAINT,

  /** A key on the layer object itself, such as `minzoom`, rather than in `layout` or `paint`. */
  ROOT,
}

/** A style mutation MapLibre refused. */
internal class StyleMutationException(message: String?, cause: Throwable?) :
  RuntimeException(message, cause)
