@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.style

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeometryTileProvider
import org.maplibre.compose.sources.VectorTileProvider

/** A source installed in exactly one loaded-style generation. */
internal class SourceInstallation(
  private val style: StyleBinding,
  definition: SourceDefinition,
) {
  private val current = AtomicReference(InstalledDefinition(0L, definition))
  private val geometryProvider =
    AtomicReference((definition as? SourceDefinition.CustomGeometry)?.provider)
  private val vectorProvider =
    AtomicReference((definition as? SourceDefinition.CustomVector)?.provider)
  private val forwardingGeometryProvider = GeometryTileProvider { tile ->
    checkNotNull(geometryProvider.load()) { "Custom geometry source '$id' has no provider" }
      .loadTile(tile)
  }
  private val forwardingVectorProvider = VectorTileProvider { tile ->
    checkNotNull(vectorProvider.load()) { "Custom vector source '$id' has no provider" }
      .loadTile(tile)
  }
  private val dataGeneration = AtomicLong(0L)
  private val pendingGeoJson = AtomicReference<PendingGeoJson?>(null)
  private val geoJsonMutex = Mutex()

  val id: String = definition.id

  init {
    check(style.sourceExists(id) != true) { "Source ID '$id' already exists in style" }
    val added =
      try {
        style.addSource(definition.forInstallation())
      } catch (error: StyleMutationException) {
        throw IllegalStateException("Could not add source '$id': ${error.message}", error)
      }
    check(added) { "Source '$id' was not added because its style is no longer loaded" }
  }

  suspend fun update(definition: SourceDefinition) {
    style.requireCurrent()
    require(definition.id == id) { "A source handle cannot change resource identity" }
    val previousDefinition = current.load().definition
    if (definition == previousDefinition) return
    when {
      previousDefinition is SourceDefinition.GeoJson && definition is SourceDefinition.GeoJson -> {
        require(previousDefinition.options == definition.options) {
          "GeoJSON source options cannot change without replacing source '$id'"
        }
        publishGeoJson(definition)
      }
      previousDefinition is SourceDefinition.Image && definition is SourceDefinition.Image -> {
        val previous = previousDefinition
        if (previous.coordinates != definition.coordinates) {
          style.setImageSourceCoordinates(id, definition.coordinates)
        }
        if (
          previous.image != definition.image || previous.value["url"] != definition.value["url"]
        ) {
          definition.image?.let { style.setImageSourceImage(id, it.toImageBitmap()) }
            ?: style.setImageSourceUrl(
              id,
              (definition.value["url"] as? JsonPrimitive)?.content.orEmpty(),
            )
        }
        storeDefinition(definition)
      }
      previousDefinition is SourceDefinition.CustomGeometry &&
        definition is SourceDefinition.CustomGeometry -> {
        require(previousDefinition.options == definition.options) {
          "Custom geometry source options cannot change without replacing source '$id'"
        }
        geometryProvider.store(definition.provider)
        storeDefinition(definition)
      }
      previousDefinition is SourceDefinition.CustomVector &&
        definition is SourceDefinition.CustomVector -> {
        require(previousDefinition.options == definition.options) {
          "Custom vector source options cannot change without replacing source '$id'"
        }
        vectorProvider.store(definition.provider)
        storeDefinition(definition)
      }
      else -> error("Source '$id' changed type or immutable options while it was installed")
    }
  }

  fun remove() {
    style.requireCurrent()
    style.removeSource(id)
  }

  private suspend fun publishGeoJson(definition: SourceDefinition.GeoJson) {
    val generation = dataGeneration.incrementAndFetch()
    val data = definition.data
    if (data is GeoJsonData.Uri) {
      pendingGeoJson.store(null)
      withContext(NonCancellable) {
        style.setGeoJsonSourceUrl(id, data.uri) { claimGeoJson(generation, definition) }
      }
      return
    }
    storePendingIfNewer(PendingGeoJson(generation, definition))
    geoJsonMutex.withLock {
      val pending = pendingGeoJson.exchange(null) ?: return
      val prepared =
        withContext(Dispatchers.Default) {
          style.prepareGeoJson(pending.definition.data, pending.definition.options)
        }
      prepared.use {
        withContext(NonCancellable) {
          style.setGeoJsonSourceData(id, prepared) {
            claimGeoJson(pending.generation, pending.definition)
          }
        }
      }
    }
  }

  private fun claimGeoJson(
    generation: Long,
    definition: SourceDefinition.GeoJson,
  ): Boolean {
    while (true) {
      val installed = current.load()
      if (generation <= installed.generation) return false
      if (current.compareAndSet(installed, InstalledDefinition(generation, definition))) return true
    }
  }

  private fun storeDefinition(definition: SourceDefinition) {
    while (true) {
      val installed = current.load()
      if (current.compareAndSet(installed, installed.copy(definition = definition))) return
    }
  }

  private fun storePendingIfNewer(next: PendingGeoJson) {
    while (true) {
      val current = pendingGeoJson.load()
      if (current != null && current.generation >= next.generation) return
      if (pendingGeoJson.compareAndSet(current, next)) return
    }
  }

  private fun SourceDefinition.forInstallation(): SourceDefinition =
    when (this) {
      is SourceDefinition.CustomGeometry -> copy(provider = forwardingGeometryProvider)
      is SourceDefinition.CustomVector -> copy(provider = forwardingVectorProvider)
      else -> this
    }

  private data class PendingGeoJson(
    val generation: Long,
    val definition: SourceDefinition.GeoJson,
  )

  private data class InstalledDefinition(
    val generation: Long,
    val definition: SourceDefinition,
  )
}

/**
 * A layer installed in exactly one loaded-style generation. The animator duration scale scales the
 * layer's paint transitions for the engine; a changed scale rewrites them on the next update.
 */
internal class LayerInstallation(
  private val style: StyleBinding,
  definition: LayerDefinition,
  beforeLayerId: String,
  animatorDurationScale: Float = 1f,
) {
  val id: String = definition.id
  private var current = definition.resolveFor(style, animatorDurationScale)
  private val reportedUnsupported = mutableSetOf<String>()

  init {
    check(style.layerExists(id) != true) { "Layer ID '$id' already exists in style" }
    add(current, beforeLayerId)
    reportUnsupported(definition)
  }

  fun update(definition: LayerDefinition, animatorDurationScale: Float = 1f) {
    style.requireCurrent()
    require(definition.id == id) { "A layer handle cannot change resource identity" }
    val next = definition.resolveFor(style, animatorDurationScale)
    if (next == current) return
    val previousValue = current.value
    val nextValue = next.value
    updateProperties(
      previousValue["layout"] as? JsonObject,
      nextValue["layout"] as? JsonObject,
      LayerPropertyKind.LAYOUT,
    )
    updateProperties(
      previousValue["paint"] as? JsonObject,
      nextValue["paint"] as? JsonObject,
      LayerPropertyKind.PAINT,
    )
    ROOT_PROPERTY_NAMES.forEach { name ->
      val previous = previousValue[name]
      val value = nextValue[name]
      if (previous == value) return@forEach
      if (name == "filter") style.setLayerFilter(id, value ?: JsonNull)
      else style.setLayerProperty(id, name, value ?: JsonNull, LayerPropertyKind.ROOT)
    }
    current = next
    reportUnsupported(definition)
  }

  fun remove() {
    style.requireCurrent()
    style.removeLayer(id)
  }

  fun move(beforeLayerId: String) {
    style.requireCurrent()
    style.moveLayer(id, beforeLayerId)
  }

  private fun add(definition: LayerDefinition, beforeLayerId: String) {
    val added =
      try {
        style.addLayer(definition, beforeLayerId)
      } catch (error: StyleMutationException) {
        throw IllegalStateException(
          "Could not add layer '$id' of type '${definition.type}'" +
            (definition.sourceId?.let { " over source '$it'" } ?: "") +
            ": ${error.message}. Layer JSON: ${definition.value}",
          error,
        )
      }
    check(added) { "Layer '$id' was not added because its style is no longer loaded" }
  }

  private fun updateProperties(
    previous: JsonObject?,
    next: JsonObject?,
    kind: LayerPropertyKind,
  ) {
    // A transition goes before the value it times, so an engine that updates between the two
    // writes animates the new value with the new timing.
    val names = previous.orEmpty().keys + next.orEmpty().keys
    names
      .sortedByDescending { it.endsWith(TRANSITION_SUFFIX) }
      .forEach { name ->
        val oldValue = previous?.get(name)
        val newValue = next?.get(name)
        if (oldValue == newValue) return@forEach
        try {
          style.setLayerProperty(id, name, newValue ?: clearingValue(kind, name), kind)
        } catch (error: StyleMutationException) {
          style.logger?.w(error) {
            "Layer '$id' of type '${current.type}' kept its previous '$name': MapLibre rejected " +
              "$newValue."
          }
        }
      }
  }

  private fun reportUnsupported(definition: LayerDefinition) {
    definition.unsupportedProperties.forEach { (name, reason) ->
      if (reportedUnsupported.add(name)) {
        style.logger?.w { "Layer '$id' of type '${definition.type}' cannot set '$name': $reason" }
      }
    }
    listOf("layout", "paint").forEach { section ->
      (definition.value[section] as? JsonObject)?.forEach { (name, value) ->
        val reason = style.unsupportedLayerPropertyReason(definition.type, name)
        if (reason != null && value !is JsonNull && reportedUnsupported.add(name)) {
          style.logger?.w {
            "Layer '$id' of type '${definition.type}' cannot set '$name': $reason"
          }
        }
      }
    }
  }

  private companion object {
    val ROOT_PROPERTY_NAMES = setOf("source-layer", "minzoom", "maxzoom", "filter")
  }
}

/**
 * The value that removes [name]. MapLibre Native rejects a null transition and keeps the previous
 * one, so a transition that goes away is cleared with an empty object.
 */
private fun clearingValue(kind: LayerPropertyKind, name: String): JsonElement =
  if (kind == LayerPropertyKind.PAINT && name.endsWith(TRANSITION_SUFFIX)) CLEARED_TRANSITION
  else JsonNull

/**
 * The layer JSON that [style] receives: without the properties its engine does not support, and
 * with every paint transition scaled by [animatorDurationScale].
 */
private fun LayerDefinition.resolveFor(
  style: StyleBinding,
  animatorDurationScale: Float,
): LayerDefinition {
  fun JsonObject.withoutUnsupported(): JsonObject =
    JsonObject(filterKeys { style.unsupportedLayerPropertyReason(type, it) == null })

  val resolved = value.toMutableMap()
  (resolved["layout"] as? JsonObject)?.withoutUnsupported()?.let {
    if (it.isEmpty()) resolved.remove("layout") else resolved["layout"] = it
  }
  (resolved["paint"] as? JsonObject)
    ?.withoutUnsupported()
    ?.withScaledTransitions(animatorDurationScale)
    ?.let { if (it.isEmpty()) resolved.remove("paint") else resolved["paint"] = it }
  return copy(value = JsonObject(resolved))
}
