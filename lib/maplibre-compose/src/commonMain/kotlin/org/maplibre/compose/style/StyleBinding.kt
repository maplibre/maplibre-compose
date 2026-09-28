package org.maplibre.compose.style

import androidx.compose.ui.graphics.ImageBitmap
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
 *
 * A property write does not block waiting for the owner: MapLibre Native posts it to the map's
 * owner thread, or applies it inline when already there. MapLibre GL JS applies it during the call.
 * The engine's rejection of such a write is logged through [reportRejectedWrite]. Source definition
 * updates run synchronously and throw on refusal; imperative handles queue them with
 * [postSourceUpdate]. A structural command, such as adding a source, layer, or image, waits for the
 * engine and throws [StyleMutationException] on refusal.
 */
internal interface StyleBinding {
  /** Identifies the loaded base-style generation for this binding. */
  val identity: StyleIdentity

  /** Immutable base resources captured before the binding is published or composition runs. */
  val baseSources: Map<String, Source?>

  /** Base-style layers in stack order. */
  val baseLayers: List<LayerSummary>

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

  /**
   * Runs [action] where this binding's synchronous operations execute inline, and suspends until it
   * has run. On MapLibre Native that is the map's owner thread, so the calls [action] makes need no
   * round trip each and the caller's thread never waits on the owner. MapLibre GL JS runs [action]
   * during the call.
   *
   * @return the result, or null when the style has unloaded or the owner stops before [action]
   *   runs. An operation inside [action] still fails if the style unloads while it runs.
   */
  suspend fun <T> awaitOwner(action: () -> T): T? = if (isLoaded) action() else null

  /**
   * Adds an image, or replaces the image with its ID in place. A replacement never shows a frame
   * without the image, which a remove followed by an add does on an engine that renders between the
   * two.
   */
  fun setImage(definition: StyleImageDefinition)

  /**
   * Installs a batch in one owner operation, retaining an independent result for each image. An
   * engine that converts pixels before the upload does so off the caller, so this function suspends
   * and must not run inside [awaitOwner].
   */
  suspend fun setImages(definitions: List<StyleImageDefinition>): List<Result<Unit>> =
    definitions.map {
      runCatching { setImage(it) }
    }

  fun setImage(id: String, image: ImageBitmap, sdf: Boolean, stretch: ImageStretch?) {
    setImage(StyleImageDefinition(id, ImageSnapshot.capture(image), sdf, stretch))
  }

  /** @return whether [id] was in the style. */
  fun removeImage(id: String): Boolean

  /** @return whether [id] exists, or null when the loaded style became unavailable. */
  fun imageExists(id: String): Boolean?

  fun getSource(id: String): Source?

  fun getSources(): List<Source>

  fun sourceIds(): List<String> = getSources().map { it.id }

  fun getLayer(id: String): LayerDefinition?

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

  /**
   * Applies a batch of layer property writes together.
   *
   * The default applies them one by one; an engine with per-call overhead can override this to
   * apply them in one pass, possibly after this function returns. A write the engine rejects is
   * logged and skipped: it does not fail the batch, the remaining writes, or the revision.
   */
  fun setLayerProperties(writes: List<LayerPropertyWrite>) {
    writes.forEach { write ->
      try {
        if (write.kind == LayerPropertyKind.ROOT && write.name == "filter") {
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

  /** @return null if the style has unloaded or the layer has no value for [name]. */
  suspend fun layerProperty(layerId: String, name: String): JsonElement?

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

  /** @return the loaded style's global transition, or null if the style has unloaded. */
  suspend fun transition(): TransitionOptions?

  /**
   * Replaces the loaded style's global transition. The write runs on the engine's thread, possibly
   * after this function returns.
   */
  fun setTransition(options: TransitionOptions)

  /** Returns true if this engine can switch the symbol placement cross-fade at runtime. */
  val supportsPlacementTransitions: Boolean

  /**
   * @return whether symbol placement changes cross-fade, or null if the style has unloaded. An
   *   engine without [supportsPlacementTransitions] reports true.
   */
  suspend fun placementTransitions(): Boolean?

  /**
   * Sets whether symbol placement changes cross-fade. The write runs on the engine's thread,
   * possibly after this function returns. An engine without [supportsPlacementTransitions] logs a
   * warning and keeps the cross-fade.
   */
  fun setPlacementTransitions(enabled: Boolean)

  /** Reads effective global values, or null when the style has unloaded. */
  suspend fun globalState(): JsonObject?

  /** Posts a global-state write. JSON null restores the root default, or null if absent. */
  fun setGlobalStateProperty(name: String, value: JsonElement)

  /** @return null if the style has unloaded or the style light sets no value for [name]. */
  suspend fun lightProperty(name: String): JsonElement?

  /**
   * Replaces the style light. A property absent from [light] returns to its spec default. The write
   * runs on the engine's thread, possibly after this function returns. A light the engine rejects
   * is reported through [reportRejectedWrite] and leaves the previous light in place.
   */
  fun setLight(light: JsonObject)

  /** Returns true if this engine supports the style sky. */
  val supportsSky: Boolean

  /**
   * @return null if the style has unloaded or the style sky sets no value for [name]. An engine
   *   without [supportsSky] reports null.
   */
  suspend fun skyProperty(name: String): JsonElement?

  /**
   * Replaces the style sky. A property absent from [sky] returns to its spec default; a null [sky]
   * removes the sky. The write runs on the engine's thread, possibly after this function returns. A
   * sky the engine rejects is reported through [reportRejectedWrite] and leaves the previous sky in
   * place. An engine without [supportsSky] logs a warning.
   */
  fun setSky(sky: JsonObject?)

  /** Returns true if this engine supports projections other than Mercator. */
  val supportsProjection: Boolean

  /**
   * @return null if the style has unloaded or the style projection sets no value for [name]. An
   *   engine without [supportsProjection] reports null.
   */
  suspend fun projectionProperty(name: String): JsonElement?

  /**
   * Replaces the style projection. A property absent from [projection] returns to its spec default.
   * The write runs on the engine's thread, possibly after this function returns. A projection the
   * engine rejects is reported through [reportRejectedWrite] and leaves the previous projection in
   * place. An engine without [supportsProjection] logs a warning and keeps Mercator.
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

  /** Queues an imperative write for the installation captured by the source handle. */
  fun postSourceUpdate(sourceId: String, resourceIdentity: Any, action: () -> Unit) {
    if (identity.sources.isCurrent(sourceId, resourceIdentity)) action()
  }

  /** Prepares owned pixels on the caller; the returned command applies them synchronously. */
  fun prepareImageSourceUpdate(sourceId: String, image: ImageSnapshot): () -> Unit

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

  /**
   * Captures [state] on the caller; the returned command merges it into one feature's state. A null
   * value drops that key. Submit the command through [postSourceUpdate] to preserve the source
   * installation's identity and report an engine rejection.
   */
  fun prepareFeatureStateUpdate(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    state: JsonObject,
  ): () -> Unit

  /** @return an empty object when the feature has no state, or the style has unloaded. */
  suspend fun featureState(sourceId: String, sourceLayerId: String?, featureId: String): JsonObject

  /**
   * Removes one key of a feature's state, or the whole state when [stateKey] is null. The write
   * runs on the engine's thread, possibly after this function returns.
   */
  fun removeFeatureState(
    sourceId: String,
    sourceLayerId: String?,
    featureId: String,
    stateKey: String?,
  )

  /**
   * Removes the state of every feature in a source, or in one of its source layers. The write runs
   * on the engine's thread, possibly after this function returns.
   */
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
  LAYOUT,
  PAINT,

  /** Identifies a key on the layer object, such as `minzoom`, outside `layout` and `paint`. */
  ROOT,
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
