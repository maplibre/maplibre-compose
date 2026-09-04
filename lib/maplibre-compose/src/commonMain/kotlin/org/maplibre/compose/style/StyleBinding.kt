package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.logging.MapLog
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
 * Provides engine operations for one loaded style and its opaque generation identifier.
 *
 * Style unload invalidates the binding. An operation on an invalid binding produces a stale-style
 * error.
 */
internal interface StyleBinding {
  /** Identifies the loaded base-style generation for this binding. */
  val identity: StyleIdentity

  val isLoaded: Boolean

  /** Invalidates this loaded style before its base style starts changing. */
  fun invalidate()

  fun requireCurrent() {
    check(isLoaded) {
      "Style operation belongs to a stale loaded-style identity"
    }
  }

  fun requireCurrent(expectedIdentity: StyleIdentity) {
    check(identity === expectedIdentity && isLoaded) {
      "Style operation belongs to a stale loaded-style identity"
    }
  }

  val logger: MapLog?

  fun addImage(definition: StyleImageDefinition)

  fun addImage(id: String, image: ImageBitmap, sdf: Boolean, stretch: ImageStretch?) {
    addImage(StyleImageDefinition(id, ImageSnapshot.capture(image), sdf, stretch))
  }

  fun removeImage(id: String)

  /** @return whether [id] exists, or null when the loaded style became unavailable. */
  fun imageExists(id: String): Boolean?

  fun getSource(id: String): Source?

  fun getSources(): List<Source>

  fun getLayer(id: String): Layer?

  fun getLayers(): List<Layer>

  fun layerIds(): List<String>

  /**
   * Adds a complete layer object directly below [beforeLayerId], or on top when that is empty.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if the engine returns an error.
   */
  fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean

  fun addLayer(definition: LayerDefinition, beforeLayerId: String): Boolean {
    requireCurrent()
    return addLayer(definition.value, beforeLayerId)
  }

  fun removeLayer(layerId: String)

  /** Moves a layer directly below [beforeLayerId], or to the top when that ID is empty. */
  fun moveLayer(layerId: String, beforeLayerId: String)

  /**
   * Sets one property on a layer that is already in the style.
   *
   * @param kind The section of the layer object that contains [name]. The engine returns an error
   *   for an incorrect section.
   * @throws StyleMutationException if the engine returns an error. An error does not change the
   *   previous value.
   */
  fun setLayerProperty(layerId: String, name: String, value: JsonElement, kind: LayerPropertyKind)

  fun setLayerFilter(layerId: String, filter: JsonElement)

  /** @return null if the style has unloaded or the layer has no value for [name]. */
  fun layerProperty(layerId: String, name: String): JsonElement?

  /**
   * Checks whether the style contains a live layer with [layerId]. Callers use the result to report
   * a specific duplicate-layer error.
   *
   * @return null if the style has unloaded or the implementation cannot determine the result. The
   *   engine still rejects a duplicate during insertion.
   */
  fun layerExists(layerId: String): Boolean?

  /**
   * The platform's animator duration scale, read once when this style loaded. Every transition
   * timing written to the engine for this style is multiplied by it, and every timing a typed
   * getter reports is divided by it, so a write and a later read agree even after the system
   * setting changes. A change to the setting applies to the next style load.
   */
  val animatorDurationScale: Float

  /** @return the loaded style's global transition, or null if the style has unloaded. */
  fun transition(): TransitionOptions?

  /** Replaces the loaded style's global transition. */
  fun setTransition(options: TransitionOptions)

  /** Returns true if this engine can switch the symbol placement cross-fade at runtime. */
  val supportsPlacementTransitions: Boolean

  /**
   * @return whether symbol placement changes cross-fade, or null if the style has unloaded. An
   *   engine without [supportsPlacementTransitions] reports true.
   */
  fun placementTransitions(): Boolean?

  /** An engine without [supportsPlacementTransitions] logs a warning and keeps the cross-fade. */
  fun setPlacementTransitions(enabled: Boolean)

  /** @return null if the style has unloaded or the style light sets no value for [name]. */
  fun lightProperty(name: String): JsonElement?

  /**
   * Replaces the style light. A property absent from [light] returns to its spec default.
   *
   * @throws StyleMutationException if the engine returns an error. An error does not change the
   *   previous value.
   */
  fun setLight(light: JsonObject)

  /** Returns true if this engine supports the style sky. */
  val supportsSky: Boolean

  /**
   * @return null if the style has unloaded or the style sky sets no value for [name]. An engine
   *   without [supportsSky] reports null.
   */
  fun skyProperty(name: String): JsonElement?

  /**
   * Replaces the style sky. A property absent from [sky] returns to its spec default; a null [sky]
   * removes the sky. An engine without [supportsSky] logs a warning.
   *
   * @throws StyleMutationException if the engine returns an error. An error does not change the
   *   previous value.
   */
  fun setSky(sky: JsonObject?)

  /** Returns true if this engine supports projections other than Mercator. */
  val supportsProjection: Boolean

  /**
   * @return null if the style has unloaded or the style projection sets no value for [name]. An
   *   engine without [supportsProjection] reports null.
   */
  fun projectionProperty(name: String): JsonElement?

  /**
   * Replaces the style projection. A property absent from [projection] returns to its spec default.
   * An engine without [supportsProjection] logs a warning and keeps Mercator.
   *
   * @throws StyleMutationException if the engine returns an error. An error does not change the
   *   previous value.
   */
  fun setProjection(projection: JsonObject)

  /**
   * Returns the reason that this engine does not support a layer property.
   *
   * A null result means that the property is supported. A non-null result omits the property from
   * writes and produces one warning for the layer.
   */
  fun unsupportedLayerPropertyReason(layerType: String, name: String): String? = null

  /**
   * Returns true if this engine decodes custom encoding factors for raster DEM sources. An
   * unsupported custom encoding uses the Mapbox encoding.
   */
  val supportsCustomDemEncoding: Boolean

  /**
   * Returns true if this engine accepts `scheme` on a raster DEM source. The style spec omits it.
   */
  val supportsRasterDemScheme: Boolean

  /**
   * Adds a source from its style-spec definition.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if the engine returns an error.
   */
  fun addSource(sourceId: String, source: JsonObject): Boolean

  /** Installs an immutable source definition in this loaded style. */
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

  /** Removes a source and its feature state. */
  fun removeSource(sourceId: String)

  /**
   * Checks whether the style contains a live source with [sourceId]. Callers use the result to
   * report a specific duplicate-source error.
   *
   * @return null if the style has unloaded or the implementation cannot determine the result. The
   *   engine still rejects a duplicate during insertion.
   */
  fun sourceExists(sourceId: String): Boolean?

  /**
   * Adds an image source from pixel data when source JSON only supports a URL.
   *
   * @param coordinates the four corners in MapLibre's order: top left, top right, bottom right,
   *   bottom left.
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if the engine returns an error.
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

  /** Sets an image source's four corners in MapLibre order. */
  fun setImageSourceCoordinates(sourceId: String, coordinates: List<Position>)

  /** @return null if the style has unloaded, or the source is not a live image source. */
  fun imageSourceCoordinates(sourceId: String): List<Position>?

  /**
   * Adds a GeoJSON source from its data and options. The default implementation writes style-spec
   * JSON. An engine can override this function to use a typed API.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if the engine returns an error for the source or its data.
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
   * Converts inline [data] to the engine-specific installation form on the calling thread.
   *
   * Callers can run this function outside the map owner thread. Use [setGeoJsonSourceUrl] for a
   * URL.
   */
  fun prepareGeoJson(data: GeoJsonData, options: GeoJsonOptions): PreparedGeoJson

  /** Prepares an update with the options currently applied to [sourceId]. */
  fun prepareGeoJsonUpdate(
    sourceId: String,
    data: GeoJsonData,
    fallbackOptions: GeoJsonOptions,
  ): PreparedGeoJson = prepareGeoJson(data, fallbackOptions)

  /**
   * Installs [prepared] on a live GeoJSON source if [claim] returns true.
   *
   * The implementation invokes [claim] in the serialized installation context. It invokes [claim]
   * even if the style has unloaded or the implementation discards the installation. This function
   * returns after installation or disposal. The caller can then close [prepared].
   */
  fun setGeoJsonSourceData(sourceId: String, prepared: PreparedGeoJson, claim: () -> Boolean)

  /** Sets [url] on a live GeoJSON source. [claim] follows [setGeoJsonSourceData]. */
  fun setGeoJsonSourceUrl(sourceId: String, url: String, claim: () -> Boolean)

  /**
   * Adds a custom geometry source that obtains feature tiles from [provider]. Removal or style
   * unload stops the source and releases its resources.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws UnsupportedOperationException on an engine with no custom geometry source (MapLibre GL
   *   JS).
   * @throws StyleMutationException if the engine returns an error.
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
   * Adds a custom vector source that obtains MVT tiles from [provider]. Removal or style unload
   * stops the source and releases its resources.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if the engine returns an error.
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
   * Returns the cluster expansion zoom for [feature].
   *
   * @return null if the feature has no cluster ID, the source is unavailable, or the cluster no
   *   longer exists.
   */
  suspend fun clusterExpansionZoom(sourceId: String, feature: Feature<*, JsonObject?>): Double?

  /**
   * Returns the cluster children for [feature], or null under [clusterExpansionZoom] conditions.
   */
  suspend fun clusterChildren(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): FeatureCollection<Geometry, JsonObject?>?

  /** Returns the cluster leaves for [feature], or null under [clusterExpansionZoom] conditions. */
  suspend fun clusterLeaves(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
    limit: Long,
    offset: Long,
  ): FeatureCollection<Geometry, JsonObject?>?

  /**
   * Reports the addition or removal of [sourceId] without waiting for an idle event.
   *
   * An asynchronous implementation calls this function after it completes the addition or removal.
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

/** Stores one GeoJSON payload in an engine-specific installation form. [close] releases it. */
internal interface PreparedGeoJson : AutoCloseable

/** Represents a GeoJSON payload that requires no preparation. */
internal object NoPreparedGeoJson : PreparedGeoJson {
  override fun close() = Unit
}

/** Identifies the section of a layer object that contains a property. */
internal enum class LayerPropertyKind {
  LAYOUT,
  PAINT,

  /** Identifies a key on the layer object, such as `minzoom`, outside `layout` and `paint`. */
  ROOT,
}

/** Reports an engine error from a style mutation. */
internal class StyleMutationException(message: String?, cause: Throwable?) :
  RuntimeException(message, cause)
