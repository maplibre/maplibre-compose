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
 * Writes are submitted on main and the engine runs them in submission order. A structural write
 * returns a [StyleCommand] with the engine's outcome; any other write that the engine rejects keeps
 * the previous value and is logged. Suspending reads observe every write submitted before them.
 * GeoJSON preparation remains asynchronous.
 */
internal interface StyleBinding {
  /** Identifies the loaded base-style generation for this binding. */
  val identity: StyleIdentity

  /** Immutable base resources captured before the binding is published or composition runs. */
  val baseSources: Map<String, Source>

  /** Base-style layers in stack order. */
  val baseLayers: List<LayerSummary>

  val isLoaded: Boolean

  /** The sources and layers that main has submitted to this style. */
  val contents: StyleContents

  /** Invalidates this loaded style before its base style starts changing. */
  fun invalidate()

  /**
   * @throws IllegalStateException when the style has unloaded. Handles check first and never let it
   *   reach the caller.
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
   * Adds an image, or replaces the image with its ID in place. A replacement never shows a frame
   * without the image, which a remove followed by an add does on an engine that renders between the
   * two.
   */
  fun setImage(definition: StyleImageDefinition): StyleCommand

  fun removeImage(id: String): StyleCommand

  /** @return whether [id] exists, or null when the implementation cannot determine the result. */
  suspend fun imageExists(id: String): Boolean?

  suspend fun getSource(id: String): Source?

  suspend fun sourceIds(): List<String>

  /** Captures source metadata with one ordered query on native. */
  suspend fun sources(): Map<String, Source> =
    sourceIds()
      .mapNotNull { id ->
        getSource(id)?.let { id to it }
      }
      .toMap()

  suspend fun getLayer(id: String): LayerDefinition?

  suspend fun layerIds(): List<String>

  suspend fun layerSummaries(): List<LayerSummary> =
    layerIds().mapNotNull { getLayer(it)?.summary() }

  /** Adds a complete layer object directly below [beforeLayerId], or on top when that is empty. */
  fun addLayer(layer: JsonObject, beforeLayerId: String): StyleCommand

  fun addLayer(definition: LayerDefinition, beforeLayerId: String): StyleCommand {
    requireCurrent()
    return addLayer(definition.value, beforeLayerId)
  }

  fun removeLayer(layerId: String): StyleCommand

  /** Moves a layer directly below [beforeLayerId], or to the top when that ID is empty. */
  fun moveLayer(layerId: String, beforeLayerId: String): StyleCommand

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
  suspend fun layerProperty(layerId: String, name: String): JsonElement?

  /**
   * The platform's current animator duration scale. Every transition the library writes to this
   * style is multiplied by it at the time of the write. A composition reads it as snapshot state,
   * so a change republishes the composed layers under the new scale.
   */
  val animatorDurationScale: Float

  /** @return the loaded style's global transition. */
  suspend fun transition(): TransitionOptions?

  /** Replaces the loaded style's global transition. */
  fun setTransition(options: TransitionOptions)

  /** Returns true if this engine can switch the symbol placement cross-fade at runtime. */
  val supportsPlacementTransitions: Boolean

  /**
   * @return whether symbol placement changes cross-fade. An engine without
   *   [supportsPlacementTransitions] reports true.
   */
  suspend fun placementTransitions(): Boolean?

  /**
   * Sets whether symbol placement changes cross-fade. An engine without
   * [supportsPlacementTransitions] logs a warning and keeps the cross-fade.
   */
  fun setPlacementTransitions(enabled: Boolean)

  /** Reads effective global values. */
  suspend fun globalState(): JsonObject?

  /** Writes global state. JSON null restores the root default, or null if absent. */
  fun setGlobalStateProperty(name: String, value: JsonElement)

  /** @return null if the style light sets no value for [name]. */
  suspend fun lightProperty(name: String): JsonElement?

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
  suspend fun skyProperty(name: String): JsonElement?

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
  suspend fun projectionProperty(name: String): JsonElement?

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

  /** Adds a source from its style-spec definition. */
  fun addSource(sourceId: String, source: JsonObject): StyleCommand

  /** Installs an immutable source definition in this loaded style. */
  fun addSource(definition: SourceDefinition): StyleCommand {
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
      is SourceDefinition.RasterDem -> {
        val json =
          try {
            rasterDemSourceJson(
              tiles = definition.tiles,
              options = definition.options,
              tileSize = definition.tileSize,
              decoding = definition.decoding,
              capabilities =
                RasterDemCapabilities(supportsCustomDemEncoding, supportsRasterDemScheme),
            )
          } catch (error: StyleMutationException) {
            return rejectedStyleCommand(error)
          }
        addSource(definition.id, json)
      }
    }
  }

  /**
   * Removes a source and its feature state. The source's data providers stop once the engine has
   * removed it; an engine that refuses the removal, such as for a source a layer still uses, keeps
   * them.
   */
  fun removeSource(sourceId: String): StyleCommand

  /**
   * Adds an image source from prepared pixels, which source JSON cannot carry.
   *
   * @param coordinates the four corners in MapLibre's order: top left, top right, bottom right,
   *   bottom left.
   */
  fun addImageSourceImage(
    sourceId: String,
    coordinates: List<Position>,
    image: PreparedImage,
  ): StyleCommand

  /** Replaces an image source's content with prepared pixels. */
  fun setImageSourceImage(sourceId: String, image: PreparedImage)

  /** Replaces an image source's content with a URL. */
  fun setImageSourceUrl(sourceId: String, url: String)

  /** Sets an image source's four corners in MapLibre order. */
  fun setImageSourceCoordinates(sourceId: String, coordinates: List<Position>)

  /**
   * Adds a GeoJSON source from its data and options. The default implementation writes style-spec
   * JSON. An engine can override this function to use a typed API.
   */
  fun addGeoJsonSource(sourceId: String, data: GeoJsonData, options: GeoJsonOptions): StyleCommand =
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
   */
  fun addCustomGeometrySource(
    sourceId: String,
    options: CustomGeometrySourceOptions,
    provider: GeometryTileProvider,
  ): StyleCommand

  /** Requests new features for intersecting tiles. GL JS reloads the whole source. */
  fun invalidateCustomGeometrySourceBounds(sourceId: String, bounds: BoundingBox)

  /** Requests new features for one tile. GL JS reloads the whole source. */
  fun invalidateCustomGeometrySourceTile(sourceId: String, tile: TileCoordinate)

  /**
   * Adds a custom vector source that obtains MVT tiles from [provider]. Removal or style unload
   * stops the source and releases its resources.
   */
  fun addCustomVectorSource(
    sourceId: String,
    options: CustomVectorTileSourceOptions,
    provider: VectorTileProvider,
  ): StyleCommand

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
   * value drops that key. Run it on main after checking the source installation identity.
   * Preparation does not call the engine.
   */
  fun prepareFeatureStateUpdate(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    state: JsonObject,
  ): () -> Unit

  /** @return an empty object when the feature has no state. */
  suspend fun featureState(sourceId: String, sourceLayerId: String?, featureId: String): JsonObject

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
    source = sourceId ?: value.rootString("source"),
    sourceLayer = value.rootString("source-layer"),
  )

private fun Map<String, JsonElement>.rootString(name: String): String? =
  (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

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
  val value: JsonElement,
  val kind: LayerPropertyKind,
)

/** Reports an engine error from a style mutation. */
internal class StyleMutationException(message: String?, cause: Throwable?) :
  RuntimeException(message, cause)
