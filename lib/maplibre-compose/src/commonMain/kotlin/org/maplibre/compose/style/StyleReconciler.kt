package org.maplibre.compose.style

import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.LayerHandle
import org.maplibre.compose.layers.LayerHandleImpl

/** Reconciles complete desired revisions into one loaded base-style generation. */
internal class StyleReconciler {
  private var fontScale: Float? = null

  private var binding: StyleBinding? = null
  private val sources = linkedMapOf<String, AppliedSource>()
  private val layers = linkedMapOf<String, AppliedLayer>()

  /**
   * The image this reconciler wrote for each ID it may have installed, or null while a write has
   * not been accepted and the engine may hold either image.
   */
  private val images = linkedMapOf<String, StyleImageDefinition?>()

  /**
   * The engine's layer order, bottom to top, as this reconciler's mutations leave it. Reading the
   * engine's order is a cross-thread round trip on native engines, so it is tracked locally and
   * re-read only when it may have drifted: after a binding change or a failed revision.
   */
  private var knownLayerIds: MutableList<String>? = null

  /**
   * The base-style layers of the bound generation, bottom to top, as anchor predicates see them.
   */
  private var baseLayers: List<LayerHandle> = emptyList()

  fun apply(style: StyleBinding, revision: DesiredStyleRevision): StyleResourceChanges {
    style.requireCurrent()
    if (binding !== style) reset(style)
    try {
      return applyRevision(style, revision)
    } catch (error: Throwable) {
      // A mutation may have succeeded before the failure; the tracked order is no longer trusted.
      knownLayerIds = null
      throw error
    }
  }

  private fun applyRevision(
    style: StyleBinding,
    revision: DesiredStyleRevision,
  ): StyleResourceChanges {
    revision.fontScale?.let { next ->
      if (fontScale != next) {
        style.setGlobalStateProperty(FONT_SCALE_GLOBAL_STATE, JsonPrimitive(next))
        fontScale = next
      }
    }
    val changes = StyleResourceChanges(style.identity)
    var layerOrderChanged = false
    val desiredSources = revision.sources.associateBy(SourceDefinition::id)
    val replacedSourceIds =
      sources.mapNotNullTo(mutableSetOf()) { (id, applied) ->
        desiredSources[id]?.takeIf { !applied.definition.canUpdateTo(it) }?.let { id }
      }
    val placements = hashMapOf<Anchor, Placement>()
    val placedLayers =
      revision.layers.map { desired ->
        PlacedLayer(
          desired,
          placements.getOrPut(desired.anchor) { placement(desired.anchor) },
        )
      }
    val desiredLayers = placedLayers.associateBy { it.definition.id }

    layers.values.toList().forEach { applied ->
      val desired = desiredLayers[applied.definition.id]
      if (
        desired == null ||
          desired.placement != applied.placement ||
          desired.definition.type != applied.definition.type ||
          desired.definition.sourceId != applied.definition.sourceId ||
          desired.definition.constructionProperties() !=
            applied.definition.constructionProperties() ||
          desired.definition.sourceId in replacedSourceIds
      ) {
        removeLayer(applied, changes)
      }
    }

    sources.values.toList().forEach { applied ->
      val desired = desiredSources[applied.definition.id]
      when {
        desired == null -> removeSource(applied, changes)
        applied.definition.canUpdateTo(desired) -> {
          applied.installation.update(desired)
          applied.definition = desired
        }
        else -> {
          removeSource(applied, changes)
          addSource(style, desired, changes)
        }
      }
    }
    revision.sources.forEach { definition ->
      if (definition.id !in sources) addSource(style, definition, changes)
    }

    syncImages(style, revision.images)

    placedLayers
      .groupByTo(linkedMapOf()) { it.placement }
      .forEach { (placement, group) ->
        var previousId: String? = null
        group.forEachIndexed { index, desired ->
          val id = desired.definition.id
          val nextDesiredId = group.getOrNull(index + 1)?.definition?.id
          var applied = layers[id]
          if (applied == null) {
            val before = beforeLayerId(layerIds(style), placement, previousId)
            applied =
              AppliedLayer(
                definition = desired.definition,
                placement = placement,
                installation =
                  LayerInstallation(
                    style,
                    desired.definition,
                    before,
                    revision.animatorDurationScale,
                  ),
              )
            changes.layers[id] = desired.definition.summary()
            layers[id] = applied
            layerIds(style).insertBelow(id, before)
          } else {
            applied.installation.update(desired.definition, revision.animatorDurationScale)
            applied.definition = desired.definition
            if (shouldMoveLayer(layerIds(style), placement, previousId, id, nextDesiredId)) {
              val before = beforeLayerId(layerIds(style), placement, previousId)
              if (before != id) {
                layerOrderChanged = true
                applied.installation.move(before)
                layerIds(style).also {
                  it.remove(id)
                  it.insertBelow(id, before)
                }
              }
            }
          }
          previousId = id
        }
      }
    if (changes.layers.isNotEmpty() || layerOrderChanged)
      changes.layerOrder = layerIds(style).toList()
    return changes
  }

  private fun reset(style: StyleBinding) {
    binding = style
    fontScale = null
    sources.clear()
    layers.clear()
    images.clear()
    knownLayerIds = null
    baseLayers =
      style.baseLayerSummaries().map { (id, summary) -> predicateLayerHandle(style, id, summary) }
  }

  private fun layerIds(style: StyleBinding): MutableList<String> =
    knownLayerIds ?: style.layerIds().toMutableList().also { knownLayerIds = it }

  /** Resolves [anchor] against the base-style layers of the bound generation. */
  private fun placement(anchor: Anchor): Placement =
    when (anchor) {
      is Anchor.Top -> Placement.Top
      is Anchor.Bottom -> Placement.Bottom
      is Anchor.Below ->
        baseLayers.firstOrNull { anchor.predicate(it) }?.let { Placement.Below(it.id) }
          ?: Placement.Top
      is Anchor.Above -> {
        val highest = baseLayers.indexOfLast { anchor.predicate(it) }
        when {
          highest < 0 -> Placement.Bottom
          highest == baseLayers.lastIndex -> Placement.Top
          else -> Placement.Below(baseLayers[highest + 1].id)
        }
      }
    }

  /** Places [id] directly below [beforeLayerId], or on top when that is empty. */
  private fun MutableList<String>.insertBelow(id: String, beforeLayerId: String) {
    if (beforeLayerId.isEmpty()) {
      add(id)
    } else {
      val index = indexOf(beforeLayerId)
      check(index >= 0) { "Layer ID '$beforeLayerId' not found in style" }
      add(index, id)
    }
  }

  private fun addSource(
    style: StyleBinding,
    definition: SourceDefinition,
    changes: StyleResourceChanges,
  ) {
    changes.sources.add(definition.id)
    sources[definition.id] = AppliedSource(definition, SourceInstallation(style, definition))
  }

  private fun removeSource(applied: AppliedSource, changes: StyleResourceChanges) {
    changes.sources.add(applied.definition.id)
    applied.installation.remove()
    sources.remove(applied.definition.id)
  }

  private fun removeLayer(applied: AppliedLayer, changes: StyleResourceChanges) {
    changes.layers[applied.definition.id] = null
    applied.installation.remove()
    knownLayerIds?.remove(applied.definition.id)
    layers.remove(applied.definition.id)
  }

  private fun syncImages(style: StyleBinding, desired: List<StyleImageDefinition>) {
    val desiredById = desired.associateBy(StyleImageDefinition::id)
    images.keys.toList().forEach { id ->
      if (id !in desiredById) {
        style.removeImage(id)
        style.identity.images.remove(id)
        images.remove(id)
      }
    }
    desired.forEach { definition ->
      val id = definition.id
      if (images[id] == definition) return@forEach
      // Replaced in place, so no frame renders without the image. Recorded as applied only once
      // the engine has accepted it, so a failed write is replaced again on the next revision, and
      // removed if the next revision drops the ID instead.
      images[id] = null
      style.setImage(definition)
      style.identity.images.remove(id)
      images[id] = definition
    }
  }

  private fun shouldMoveLayer(
    ids: List<String>,
    placement: Placement,
    previousId: String?,
    id: String,
    nextDesiredId: String?,
  ): Boolean {
    if (previousId != null) return ids.idAbove(previousId) != id
    if (nextDesiredId != null) return ids.idAbove(id) != nextDesiredId
    val before = beforeLayerId(ids, placement, null)
    return before != id && ids.idAbove(id) != before
  }

  /**
   * The ID a layer at [placement] is inserted below, or an empty string for the top of the stack.
   * [Placement.Bottom] reads the tracked order at insertion time, so it sits under the engine's own
   * layers as well as the base style's.
   */
  private fun beforeLayerId(ids: List<String>, placement: Placement, previousId: String?): String {
    if (previousId != null) return ids.idAbove(previousId)
    return when (placement) {
      Placement.Top -> ""
      Placement.Bottom -> ids.firstOrNull().orEmpty()
      is Placement.Below -> placement.layerId
    }
  }

  private fun List<String>.idAbove(id: String): String {
    val index = indexOf(id)
    check(index >= 0) { "Layer ID '$id' not found in style" }
    return getOrNull(index + 1).orEmpty()
  }

  private class AppliedSource(
    var definition: SourceDefinition,
    val installation: SourceInstallation,
  )

  private class AppliedLayer(
    var definition: ResolvedLayerDefinition,
    val placement: Placement,
    val installation: LayerInstallation,
  )

  private class PlacedLayer(desired: DesiredStyleLayer, val placement: Placement) {
    val definition: ResolvedLayerDefinition = desired.definition
  }

  /**
   * An anchor resolved against one base-style generation. Layers with equal placements form one
   * group in style-content order, so two anchors that resolve to the same position never move each
   * other's layers.
   */
  private sealed interface Placement {
    data object Top : Placement

    data object Bottom : Placement

    /** Directly under the base-style layer [layerId]. */
    data class Below(val layerId: String) : Placement
  }
}

/**
 * A handle for an anchor predicate. A predicate is not a suspend function, so it reaches only the
 * handle's plain values, which base layers keep for the generation. A write from a predicate would
 * mutate the base style mid-revision, so writes are refused.
 */
private fun predicateLayerHandle(
  style: StyleBinding,
  id: String,
  summary: LayerSummary,
): LayerHandle {
  val identity = style.identity.layers.get(id)
  return LayerHandleImpl(
    id = id,
    type = summary.type,
    source = summary.source,
    sourceLayer = summary.sourceLayer,
    style = style,
    isCurrentResource = { style.identity.layers.isCurrent(id, identity) },
    operations =
      object : StyleHandleOperationGuard {
        override fun <T> run(action: () -> T): T = action()

        override fun isSourceWritable(id: String): Boolean = false

        override fun isLayerWritable(id: String): Boolean = false

        override fun removeSource(id: String, identity: Any): Boolean = refuseWrite(id)

        override fun requireSourceWritable(id: String) = refuseWrite(id)

        override fun requireLayerWritable(id: String) = refuseWrite(id)

        private fun refuseWrite(id: String): Nothing =
          throw StyleHandleException("Layer '$id' is read-only in an anchor predicate")
      },
  )
}

internal fun SourceDefinition.canUpdateTo(next: SourceDefinition): Boolean =
  when {
    this is SourceDefinition.GeoJson && next is SourceDefinition.GeoJson -> options == next.options
    this is SourceDefinition.Image && next is SourceDefinition.Image -> true
    this is SourceDefinition.CustomGeometry && next is SourceDefinition.CustomGeometry ->
      options == next.options
    this is SourceDefinition.CustomVector && next is SourceDefinition.CustomVector ->
      options == next.options
    else -> this == next
  }

/**
 * Resources an applied revision inserted, removed, or moved. [layers] maps an inserted layer to its
 * summary and a removed layer to null; [layerOrder] is the engine's layer order after any insertion
 * or move, and null when neither happened.
 */
internal class StyleResourceChanges(val identity: StyleIdentity? = null) {
  val sources = linkedSetOf<String>()
  val layers = linkedMapOf<String, LayerSummary?>()
  var layerOrder: List<String>? = null
}
