package org.maplibre.compose.style

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.maplibre.compose.layers.LayerSummary
import org.maplibre.compose.logging.MapLog
import org.maplibre.compose.sources.CustomGeometrySourceOptions
import org.maplibre.compose.sources.CustomVectorTileSourceOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.TileCoordinate
import org.maplibre.compose.sources.VectorTileProvider
import org.maplibre.compose.sources.putGeoJsonOptions
import org.maplibre.compose.sources.rasterDemSourceJson
import org.maplibre.compose.sources.toDataJson
import org.maplibre.compose.style.internal.StyleValue
import org.maplibre.compose.util.PreparedImage
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
 *
 * Engine reads and mutations run inline inside [awaitOwner] or [postOwner]. Handles and commands
 * choose the visit, check resource identity, and report posted refusals. Mutations throw
 * [StyleMutationException] on engine refusal; layer batches log each refusal and continue.
 *
 * Suspending feature queries use the renderer or the browser's worker API. GeoJSON submission
 * starts worker preparation without waiting; prepared data returns to the owner for installation.
 */
internal interface StyleBinding {
  /** Identifies the loaded base-style generation for this binding. */
  val identity: StyleIdentity

  /** Immutable base resources captured before the binding is published or composition runs. */
  val baseSources: Map<String, Source>

  /** Base-style layers in stack order. */
  val baseLayers: List<LayerSummary>

  val isLoaded: Boolean

  /** Invalidates this loaded style before its base style starts changing. */
  fun invalidate()

  /**
   * @throws IllegalStateException when the style has unloaded. Handles check first and never let it
   *   reach the caller; [awaitOwner] and [postOwner] absorb it when the style unloads during a
   *   visit.
   */
  fun requireCurrent() {
    check(isLoaded) { "Style operation belongs to a stale loaded-style identity" }
  }

  /** @throws IllegalStateException when the style has unloaded or is not [expectedIdentity]. */
  fun requireCurrent(expectedIdentity: StyleIdentity) {
    check(identity === expectedIdentity && isLoaded) {
      "Style operation belongs to a stale loaded-style identity"
    }
  }

  val logger: MapLog?

  /**
   * Runs [action] where this binding's synchronous operations execute inline, and suspends until it
   * has run. On MapLibre Native that is the map's owner thread, so the calls [action] makes need no
   * round trip each and the caller's thread never waits on the owner. MapLibre GL JS runs [action]
   * during the call.
   *
   * @return the result, or null when the style has unloaded or the owner stops before [action]
   *   runs, or when the style unloads while [action] runs and [action] fails.
   */
  suspend fun <T> awaitOwner(action: () -> T): T? = if (isLoaded) action() else null

  /**
   * Runs [action] on the engine owner without waiting. It runs inline during an owner visit and is
   * dropped if this style has unloaded or unloads before it runs; [onDropped] then runs instead.
   * Callers capture and check resource identity inside [action].
   */
  fun postOwner(onDropped: () -> Unit = {}, action: () -> Unit) {
    if (isLoaded) action() else onDropped()
  }

  /**
   * Adds an image, or replaces the image with its ID in place. A replacement never shows a frame
   * without the image, which a remove followed by an add does on an engine that renders between the
   * two.
   */
  fun setImage(definition: StyleImageDefinition)

  /** @return whether [id] was in the style. */
  fun removeImage(id: String): Boolean

  /** @return whether [id] exists, or null when the implementation cannot determine the result. */
  fun imageExists(id: String): Boolean?

  fun getSource(id: String): Source?

  fun sourceIds(): List<String>

  fun getLayer(id: String): LayerDefinition?

  fun layerIds(): List<String>

  fun layerSummaries(): List<LayerSummary> = layerIds().mapNotNull { getLayer(it)?.summary() }

  /**
   * Adds a complete layer object directly below [beforeLayerId], or on top when that is empty.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if the engine returns an error.
   */
  fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean

  fun addLayer(definition: LayerDefinition, beforeLayerId: String): Boolean {
    requireCurrent()
    return addLayer(StyleValue.Object(definition.properties), beforeLayerId)
  }

  fun addLayer(layer: StyleValue, beforeLayerId: String): Boolean =
    addLayer(layer.json as JsonObject, beforeLayerId)

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

  fun setLayerProperty(layerId: String, name: String, value: StyleValue, kind: LayerPropertyKind) =
    setLayerProperty(layerId, name, value.json, kind)

  fun setLayerFilter(layerId: String, filter: StyleValue) = setLayerFilter(layerId, filter.json)

  fun setLayerFilter(layerId: String, filter: JsonElement)

  /**
   * Applies a batch of layer property writes together.
   *
   * A write the engine rejects is logged and skipped: it does not fail the batch, the remaining
   * writes, or the revision.
   */
  fun setLayerProperties(writes: List<LayerPropertyWrite>) {
    writes.forEach { write ->
      try {
        if (write.kind == LayerPropertyKind.Root && write.name == "filter") {
          setLayerFilter(write.layerId, write.value)
        } else {
          setLayerProperty(write.layerId, write.name, write.value, write.kind)
        }
      } catch (error: StyleMutationException) {
        reportRejectedWrite(write, error)
      }
    }
  }

  /** Reports a write the engine rejected during [setLayerProperties]. */
  fun reportRejectedWrite(write: LayerPropertyWrite, error: StyleMutationException) {
    logger?.w(error) {
      "Layer '${write.layerId}' of type '${write.layerType}' kept its previous '${write.name}': " +
        "MapLibre rejected ${write.value}."
    }
  }

  /**
   * Reports a posted write that the engine rejected. [target] names what kept its previous value,
   * such as `The light`; [value] is what the engine refused, or null when the write carried none.
   */
  fun reportRejectedWrite(target: String, value: JsonElement?, error: StyleMutationException) {
    logger?.w(error) {
      "$target kept its previous value: MapLibre rejected ${value ?: "the write"}."
    }
  }

  /** @return null if the layer has no value for [name]. */
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
   * The platform's current animator duration scale. Every transition the library writes to this
   * style is multiplied by it at the time of the write. A composition reads it as snapshot state,
   * so a change republishes the composed layers under the new scale.
   */
  val animatorDurationScale: Float

  /** @return the loaded style's global transition. */
  fun transition(): TransitionOptions?

  /** Replaces the loaded style's global transition. */
  fun setTransition(options: TransitionOptions)

  /** Returns true if this engine can switch the symbol placement cross-fade at runtime. */
  val supportsPlacementTransitions: Boolean

  /**
   * @return whether symbol placement changes cross-fade. An engine without
   *   [supportsPlacementTransitions] reports true.
   */
  fun placementTransitions(): Boolean?

  /**
   * Sets whether symbol placement changes cross-fade. An engine without
   * [supportsPlacementTransitions] logs a warning and keeps the cross-fade.
   */
  fun setPlacementTransitions(enabled: Boolean)

  /** Reads effective global values. */
  fun globalState(): JsonObject?

  /** Writes global state. JSON null restores the root default, or null if absent. */
  fun setGlobalStateProperty(name: String, value: JsonElement)

  /** @return null if the style light sets no value for [name]. */
  fun lightProperty(name: String): JsonElement?

  /**
   * Replaces the style light. A property absent from [light] returns to its spec default. An engine
   * refusal leaves the previous light in place.
   */
  fun setLight(light: JsonObject)

  /** Returns true if this engine supports the style sky. */
  val supportsSky: Boolean

  /**
   * @return null if the style sky sets no value for [name]. An engine without [supportsSky] reports
   *   null.
   */
  fun skyProperty(name: String): JsonElement?

  /**
   * Replaces the style sky. A property absent from [sky] returns to its spec default; a null [sky]
   * removes the sky. An engine refusal leaves the previous sky in place. An engine without
   * [supportsSky] logs a warning.
   */
  fun setSky(sky: JsonObject?)

  /** Returns true if this engine supports projections other than Mercator. */
  val supportsProjection: Boolean

  /**
   * @return null if the style projection sets no value for [name]. An engine without
   *   [supportsProjection] reports null.
   */
  fun projectionProperty(name: String): JsonElement?

  /**
   * Replaces the style projection. A property absent from [projection] returns to its spec default.
   * An engine refusal leaves the previous projection in place. An engine without
   * [supportsProjection] logs a warning and keeps Mercator.
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
        definition.image?.let { addImageSourceImage(definition.id, definition.coordinates, it) }
          ?: addSource(definition.id, definition.value)
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
            decoding = definition.decoding,
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
   * Adds an image source from prepared pixels, which source JSON cannot carry.
   *
   * @param coordinates the four corners in MapLibre's order: top left, top right, bottom right,
   *   bottom left.
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if the engine returns an error.
   */
  fun addImageSourceImage(
    sourceId: String,
    coordinates: List<Position>,
    image: PreparedImage,
  ): Boolean

  /** Replaces an image source's content with prepared pixels. */
  fun setImageSourceImage(sourceId: String, image: PreparedImage)

  /** Replaces an image source's content with a URL. */
  fun setImageSourceUrl(sourceId: String, url: String)

  /** Sets an image source's four corners in MapLibre order. */
  fun setImageSourceCoordinates(sourceId: String, coordinates: List<Position>)

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
   * Submits immutable [data] to the current installation of [sourceId].
   *
   * The binding owns preparation and ordering. A newer submission supersedes older pending data.
   * Native preparation uses the source's applied options, or [fallbackOptions] if those options are
   * unavailable. Preparation runs on a worker; native tiling policy does not change submission
   * ordering or make callers wait.
   */
  fun submitGeoJsonData(sourceId: String, data: GeoJsonData, fallbackOptions: GeoJsonOptions)

  /**
   * Adds a custom geometry source that obtains feature tiles from [provider]. Removal or style
   * unload stops the source and releases its resources.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if the engine returns an error.
   */
  fun addCustomGeometrySource(
    sourceId: String,
    options: CustomGeometrySourceOptions,
    provider: GeometryTileProvider,
  ): Boolean

  /** Requests new features for intersecting tiles. GL JS reloads the whole source. */
  fun invalidateCustomGeometrySourceBounds(sourceId: String, bounds: BoundingBox)

  /** Requests new features for one tile. GL JS reloads the whole source. */
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
    options: CustomVectorTileSourceOptions,
    provider: VectorTileProvider,
  ): Boolean

  /** Requests new data for one tile. GL JS reloads the whole source. */
  fun invalidateCustomVectorSourceTile(sourceId: String, tile: TileCoordinate)

  /**
   * Returns the cluster expansion zoom for [feature].
   *
   * @return null if the feature has no cluster ID, the source is unavailable or does not cluster
   *   its data, or the engine reports that the cluster no longer exists.
   * @throws StyleHandleException wrapping any other engine failure.
   */
  suspend fun clusterExpansionZoom(sourceId: String, feature: Feature<*, JsonObject?>): Double?

  /**
   * Returns the cluster children for [feature], or null under [clusterExpansionZoom] conditions.
   */
  suspend fun clusterChildren(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
  ): FeatureCollection<Geometry, JsonObject?>?

  /**
   * Returns the cluster leaves for [feature], or null under [clusterExpansionZoom] conditions.
   * [limit] is positive and [offset] is not negative.
   */
  suspend fun clusterLeaves(
    sourceId: String,
    feature: Feature<*, JsonObject?>,
    limit: Int,
    offset: Int,
  ): FeatureCollection<Geometry, JsonObject?>?

  /**
   * Captures [state] on the caller; the returned command merges it into one feature's state. A null
   * value drops that key. Run it inside an owner visit after checking the source installation
   * identity. Preparation does not call the engine.
   */
  fun prepareFeatureStateUpdate(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    state: JsonObject,
  ): () -> Unit

  /** @return an empty object when the feature has no state. */
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
  suspend fun querySourceFeatures(
    sourceId: String,
    sourceLayerIds: Set<String>,
    filter: JsonElement?,
  ): List<Feature<Geometry, JsonObject?>>
}

internal fun LayerDefinition.summary(): LayerSummary =
  LayerSummary(
    id = id,
    type = type,
    source = sourceId,
    sourceLayer =
      (properties["source-layer"]?.json as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull,
  )

/** Identifies the section of a layer object that contains a property. */
internal enum class LayerPropertyKind {
  Layout,
  Paint,

  /** Identifies a key on the layer object, such as `minzoom`, outside `layout` and `paint`. */
  Root,
}

/**
 * One layer property change in a reconciled revision. [layerType] is carried only so a rejection
 * can name it in a log after the write has left the reconciler's hands.
 */
internal class LayerPropertyWrite(
  val layerId: String,
  val layerType: String,
  val name: String,
  val value: StyleValue,
  val kind: LayerPropertyKind,
) {
  constructor(
    layerId: String,
    layerType: String,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ) : this(layerId, layerType, name, StyleValue.Json(value), kind)
}

/** Reports an engine error from a style mutation. */
internal class StyleMutationException(message: String?, cause: Throwable?) :
  RuntimeException(message, cause)
