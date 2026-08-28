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
import org.maplibre.compose.sources.toDataJson
import org.maplibre.compose.util.ImageStretch
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/**
 * A source or layer's connection to its live style, as an ID and style-spec JSON. A binding stops
 * working when its style unloads, after which mutations are dropped and reads answer null rather
 * than throwing.
 */
internal interface StyleBinding {
  val isLoaded: Boolean

  val logger: Logger?

  /** Runs [action] when this style unloads and returns a function that removes the action. */
  fun onUnload(action: () -> Unit): () -> Unit

  /**
   * Adds a complete layer object directly below [beforeLayerId], or on top when that is empty.
   *
   * @return false if the style has unloaded, in which case nothing was added.
   * @throws StyleMutationException if MapLibre refuses the layer.
   */
  fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean

  fun removeLayer(layerId: String)

  /** Moves a layer to sit directly below [beforeLayerId], or on top when that is empty. */
  fun moveLayer(layerId: String, beforeLayerId: String)

  /** Live layer ids in draw order, or null when the style has unloaded. */
  fun layerIds(): List<String>?

  /** Adds [layer]'s descriptor on top of the style; dropped when the style has unloaded. */
  fun addLayer(layer: Layer) {
    if (!isLoaded) return
    layer.attach(this, beforeLayerId = "")
  }

  /** Adds [layer] directly above the live layer named [layerId]. */
  fun addLayerAbove(layerId: String, layer: Layer) {
    // "Above id" is "below whatever currently sits above id", which is the next layer along.
    val ids = layerIds() ?: return
    val index = ids.indexOf(layerId)
    require(index >= 0) { "Layer ID '$layerId' not found in base style" }
    layer.attach(this, beforeLayerId = ids.getOrNull(index + 1).orEmpty())
  }

  /** Adds [layer] directly below the live layer named [layerId]. */
  fun addLayerBelow(layerId: String, layer: Layer) {
    if (!isLoaded) return
    layer.attach(this, beforeLayerId = layerId)
  }

  /** Adds [layer] at [index] in the draw order, where 0 is the bottom. */
  fun addLayerAt(index: Int, layer: Layer) {
    val ids = layerIds() ?: return
    require(index in 0..ids.size) { "Layer index $index is outside the valid range 0..${ids.size}" }
    layer.attach(this, beforeLayerId = ids.getOrNull(index).orEmpty())
  }

  /** Removes [layer]'s descriptor from the style it belongs to. */
  fun removeLayer(layer: Layer) {
    layer.detach(this)
  }

  /** A descriptor over the live layer with [id], or null if none exists or the style unloaded. */
  fun getLayer(id: String): Layer?

  /** Descriptors over the live layers in draw order; empty when the style has unloaded. */
  fun getLayers(): List<Layer>

  /**
   * Sets one property on a layer that is already in the style.
   *
   * @param kind which of a layer object's three parts [name] belongs to; a name offered to the
   *   wrong one is rejected.
   * @return whether the write reached the style; false when the style unloaded first.
   * @throws StyleMutationException if MapLibre refuses [value]; the layer keeps its previous one.
   */
  fun setLayerProperty(
    layerId: String,
    name: String,
    value: JsonElement,
    kind: LayerPropertyKind,
  ): Boolean

  /** Same acceptance contract as [setLayerProperty]. */
  fun setLayerFilter(layerId: String, filter: JsonElement): Boolean

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

  /** Adds [source]'s descriptor to this style; dropped when the style has unloaded. */
  fun addSource(source: Source) {
    if (!isLoaded) return
    source.attach(this)
  }

  /** Removes [source]'s descriptor from the style it belongs to. */
  fun removeSource(source: Source) {
    source.detach(this)
  }

  /** A descriptor over the live source with [id], or null if none exists or the style unloaded. */
  fun getSource(id: String): Source?

  /** Descriptors over the live sources; empty when the style has unloaded. */
  fun getSources(): List<Source>

  /** Adds or replaces a style image, tagged with the engine's display scale. */
  fun addImage(id: String, image: ImageBitmap, sdf: Boolean, stretch: ImageStretch?)

  fun removeImage(id: String)

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
   * in one place. It runs even when the style has unloaded or the install is dropped, so the
   * descriptor still records the data. Returns after the install has run or been dropped, so the
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

  companion object {
    /** A binding for a descriptor that has never been added to a style. */
    val UNLOADED: StyleBinding =
      object : StyleBinding {
        override val isLoaded: Boolean = false

        override val logger: Logger? = null

        override fun onUnload(action: () -> Unit): () -> Unit {
          action()
          return {}
        }

        override fun addLayer(layer: JsonObject, beforeLayerId: String): Boolean = false

        override fun removeLayer(layerId: String) = Unit

        override fun moveLayer(layerId: String, beforeLayerId: String) = Unit

        // UNLOADED accepts writes: a detached descriptor buffers them for the next attach.
        override fun setLayerProperty(
          layerId: String,
          name: String,
          value: JsonElement,
          kind: LayerPropertyKind,
        ): Boolean = true

        override fun setLayerFilter(layerId: String, filter: JsonElement): Boolean = true

        override fun layerProperty(layerId: String, name: String): JsonElement? = null

        override fun layerExists(layerId: String): Boolean? = null

        override fun layerIds(): List<String>? = null

        override fun getLayer(id: String): Layer? = null

        override fun getLayers(): List<Layer> = emptyList()

        override fun getSource(id: String): Source? = null

        override fun getSources(): List<Source> = emptyList()

        override fun addImage(
          id: String,
          image: ImageBitmap,
          sdf: Boolean,
          stretch: ImageStretch?,
        ) = Unit

        override fun removeImage(id: String) = Unit

        // Nothing reads these before a descriptor attaches; they carry the native answers.
        override val supportsCustomDemEncoding: Boolean = false

        override val supportsRasterDemScheme: Boolean = true

        override fun addSource(sourceId: String, source: JsonObject): Boolean = false

        override fun removeSource(sourceId: String) = Unit

        override fun sourceExists(sourceId: String): Boolean? = null

        override fun addImageSourceImage(
          sourceId: String,
          coordinates: List<Position>,
          image: ImageBitmap,
        ): Boolean = false

        override fun setImageSourceImage(sourceId: String, image: ImageBitmap) = Unit

        override fun setImageSourceUrl(sourceId: String, url: String) = Unit

        override fun setImageSourceCoordinates(sourceId: String, coordinates: List<Position>) = Unit

        override fun imageSourceCoordinates(sourceId: String): List<Position>? = null

        override fun prepareGeoJson(data: GeoJsonData, options: GeoJsonOptions): PreparedGeoJson =
          NoPreparedGeoJson

        override fun setGeoJsonSourceData(
          sourceId: String,
          prepared: PreparedGeoJson,
          claim: () -> Boolean,
        ) {
          claim()
        }

        override fun setGeoJsonSourceUrl(sourceId: String, url: String, claim: () -> Boolean) {
          claim()
        }

        override fun addCustomGeometrySource(
          sourceId: String,
          options: CustomGeometrySourceOptions,
          provider: GeometryTileProvider,
        ): Boolean = false

        override fun invalidateCustomGeometrySourceBounds(sourceId: String, bounds: BoundingBox) =
          Unit

        override fun invalidateCustomGeometrySourceTile(sourceId: String, tile: TileCoordinate) =
          Unit

        override fun addCustomVectorSource(
          sourceId: String,
          options: CustomVectorSourceOptions,
          provider: VectorTileProvider,
        ): Boolean = false

        override fun invalidateCustomVectorSourceTile(sourceId: String, tile: TileCoordinate) = Unit

        override suspend fun clusterExpansionZoom(
          sourceId: String,
          feature: Feature<*, JsonObject?>,
        ): Double? = null

        override suspend fun clusterChildren(
          sourceId: String,
          feature: Feature<*, JsonObject?>,
        ): FeatureCollection<Geometry, JsonObject?>? = null

        override suspend fun clusterLeaves(
          sourceId: String,
          feature: Feature<*, JsonObject?>,
          limit: Long,
          offset: Long,
        ): FeatureCollection<Geometry, JsonObject?>? = null

        override fun setFeatureState(
          sourceId: String,
          sourceLayerId: String?,
          featureId: String,
          state: JsonObject,
        ) = Unit

        override fun featureState(
          sourceId: String,
          sourceLayerId: String?,
          featureId: String,
        ): JsonObject = JsonObject(emptyMap())

        override fun removeFeatureState(
          sourceId: String,
          sourceLayerId: String?,
          featureId: String,
          stateKey: String?,
        ) = Unit

        override fun resetFeatureStates(sourceId: String, sourceLayerId: String?) = Unit

        override fun querySourceFeatures(
          sourceId: String,
          sourceLayerIds: Set<String>,
          filter: JsonElement?,
        ): List<Feature<Geometry, JsonObject?>> = emptyList()
      }
  }
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
