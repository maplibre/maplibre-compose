package org.maplibre.compose.style

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.maplibre.compose.layers.LayerSummary
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.sourceKind

/**
 * The sources and layers of one loaded style, as main has submitted them.
 *
 * It starts from the base style and follows each structural write when it is submitted. The engine
 * runs writes in submission order, so this matches the engine unless the engine rejects a write;
 * whoever submitted that write then restores what the engine kept. Sources and layers that a caller
 * adds through [MapState.withPlatformMap][org.maplibre.compose.map.withPlatformMap] are not
 * reflected. Main only.
 */
internal class StyleContents(baseSources: Map<String, Source>, baseLayers: List<LayerSummary>) {
  /** What a source handle publishes: the source's style-spec type and its attribution. */
  internal data class SourceEntry(val kind: String?, val attributionHtml: String)

  private val sourceEntries = linkedMapOf<String, SourceEntry>()
  private val layerEntries = mutableListOf<LayerSummary>()

  /** Counts layer-order changes, so an engine read can tell whether a write followed it. */
  var layerOrderVersion: Long = 0
    private set

  init {
    baseSources.forEach { (id, source) -> sourceEntries[id] = source.entry(definition = null) }
    layerEntries += baseLayers
  }

  /** Sources in submission order. */
  val sources: Map<String, SourceEntry>
    get() = sourceEntries

  /** Layers from bottom to top. */
  val layers: List<LayerSummary>
    get() = layerEntries

  fun layerIds(): List<String> = layerEntries.map { it.id }

  fun hasLayer(id: String): Boolean = layerEntries.any { it.id == id }

  fun addSource(definition: SourceDefinition) {
    sourceEntries[definition.id] = SourceEntry(sourceKind(definition, null), definition.attribution)
  }

  fun restoreSource(id: String, entry: SourceEntry) {
    sourceEntries[id] = entry
  }

  fun removeSource(id: String): SourceEntry? = sourceEntries.remove(id)

  /** Takes the engine's metadata for [id], keeping the kind its definition gave it. */
  fun updateSource(id: String, source: Source, definition: SourceDefinition?) {
    if (id in sourceEntries) sourceEntries[id] = source.entry(definition)
  }

  /** Places [layer] directly below [beforeLayerId], or on top when that is empty. */
  fun insertLayer(layer: LayerSummary, beforeLayerId: String) {
    layerEntries.add(insertionIndex(beforeLayerId), layer)
    layerOrderVersion++
  }

  fun removeLayer(id: String) {
    if (layerEntries.removeAll { it.id == id }) layerOrderVersion++
  }

  fun moveLayer(id: String, beforeLayerId: String) {
    val index = layerEntries.indexOfFirst { it.id == id }
    check(index >= 0) { "Layer ID '$id' not found in style" }
    val layer = layerEntries.removeAt(index)
    layerEntries.add(insertionIndex(beforeLayerId), layer)
    layerOrderVersion++
  }

  /** Replaces the layer order with the one the engine reported. */
  fun replaceLayers(layers: List<LayerSummary>) {
    layerEntries.clear()
    layerEntries += layers
    layerOrderVersion++
  }

  private fun insertionIndex(beforeLayerId: String): Int {
    if (beforeLayerId.isEmpty()) return layerEntries.size
    val index = layerEntries.indexOfFirst { it.id == beforeLayerId }
    check(index >= 0) { "Layer ID '$beforeLayerId' not found in style" }
    return index
  }
}

private fun Source.entry(definition: SourceDefinition?) =
  StyleContents.SourceEntry(sourceKind(definition, this), attributionHtml)

/** The attribution a definition declares, before the engine reports any of its own. */
private val SourceDefinition.attribution: String
  get() =
    when (this) {
      is SourceDefinition.Json -> (value["attribution"] as? JsonPrimitive)?.contentOrNull
      is SourceDefinition.Image -> (value["attribution"] as? JsonPrimitive)?.contentOrNull
      is SourceDefinition.RasterDem -> options.attributionHtml
      is SourceDefinition.GeoJson,
      is SourceDefinition.CustomGeometry,
      is SourceDefinition.CustomVector -> null
    }.orEmpty()
