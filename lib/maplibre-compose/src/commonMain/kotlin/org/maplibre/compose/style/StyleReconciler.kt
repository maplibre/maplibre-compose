package org.maplibre.compose.style

import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.LayerHandle

/** Reconciles complete desired revisions into one loaded base-style generation. */
internal class StyleReconciler {
  private var binding: StyleBinding? = null
  private val sources = linkedMapOf<String, AppliedSource>()
  private val layers = linkedMapOf<String, AppliedLayer>()
  private val images = linkedMapOf<String, StyleImageDefinition>()

  /**
   * The engine's layer order, bottom to top, as this reconciler's mutations leave it. Reading the
   * engine's order is a cross-thread round trip on native engines, so it is tracked locally and
   * re-read only when it may have drifted: after a binding change or a failed revision.
   */
  private var knownLayerIds: MutableList<String>? = null

  /**
   * The base-style layers of the bound generation, bottom to top, as anchor predicates see them.
   * Base layers do not change within one generation, so they are read once per binding.
   */
  private var baseLayers: List<LayerHandle>? = null

  suspend fun apply(style: StyleBinding, revision: DesiredStyleRevision): StyleResourceChanges {
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

  private suspend fun applyRevision(
    style: StyleBinding,
    revision: DesiredStyleRevision,
  ): StyleResourceChanges {
    val changes = StyleResourceChanges(style.identity)
    var layerOrderChanged = false
    val desiredSources = revision.sources.associateBy(SourceDefinition::id)
    val replacedSourceIds =
      sources.mapNotNullTo(mutableSetOf()) { (id, applied) ->
        desiredSources[id]?.takeIf { !applied.definition.canUpdateTo(it) }?.let { id }
      }
    val placements = hashMapOf<Anchor, String>()
    val placedLayers =
      revision.layers.map { desired ->
        PlacedLayer(
          desired,
          placements.getOrPut(desired.anchor) { placement(style, desired.anchor) },
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
          desired.definition.value["source-layer"] != applied.definition.value["source-layer"] ||
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
            changes.layers.add(id)
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
    sources.clear()
    layers.clear()
    images.clear()
    knownLayerIds = null
    baseLayers = null
  }

  private fun layerIds(style: StyleBinding): MutableList<String> =
    knownLayerIds ?: style.layerIds().toMutableList().also { knownLayerIds = it }

  private fun baseLayers(style: StyleBinding): List<LayerHandle> =
    baseLayers
      ?: style
        .layerTypes()
        .filterKeys { it !in layers }
        .map { (id, type) -> predicateLayerHandle(style, id, type) }
        .also { baseLayers = it }

  /**
   * Resolves [anchor] to the ID of the base-style layer that its layers sit directly below, or to
   * an empty string for the top of the stack.
   */
  private fun placement(style: StyleBinding, anchor: Anchor): String =
    when (anchor) {
      is Anchor.Top -> ""
      is Anchor.Bottom -> baseLayers(style).firstOrNull()?.id.orEmpty()
      is Anchor.Below -> baseLayers(style).firstOrNull { anchor.predicate(it) }?.id.orEmpty()
      is Anchor.Above -> {
        val base = baseLayers(style)
        val highest = base.indexOfLast { anchor.predicate(it) }
        base.getOrNull(if (highest < 0) 0 else highest + 1)?.id.orEmpty()
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
    changes.layers.add(applied.definition.id)
    applied.installation.remove()
    knownLayerIds?.remove(applied.definition.id)
    layers.remove(applied.definition.id)
  }

  private fun syncImages(style: StyleBinding, desired: List<StyleImageDefinition>) {
    val desiredById = desired.associateBy(StyleImageDefinition::id)
    images.values.toList().forEach { applied ->
      val next = desiredById[applied.id]
      if (next == null || next != applied) {
        style.removeImage(applied.id)
        images.remove(applied.id)
      }
    }
    desired.forEach { definition ->
      if (definition.id !in images) {
        style.addImage(definition)
        images[definition.id] = definition
      }
    }
  }

  private fun shouldMoveLayer(
    ids: List<String>,
    placement: String,
    previousId: String?,
    id: String,
    nextDesiredId: String?,
  ): Boolean {
    if (previousId != null) return ids.idAbove(previousId) != id
    if (nextDesiredId != null) return ids.idAbove(id) != nextDesiredId
    return placement != id && ids.idAbove(id) != placement
  }

  private fun beforeLayerId(ids: List<String>, placement: String, previousId: String?): String =
    if (previousId != null) ids.idAbove(previousId) else placement

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
    var definition: LayerDefinition,
    val placement: String,
    val installation: LayerInstallation,
  )

  private class PlacedLayer(desired: DesiredStyleLayer, val placement: String) {
    val definition: LayerDefinition = desired.definition
  }
}

/**
 * A handle for an anchor predicate. The reconciler holds the style while a predicate runs, so
 * operations run inline; a write from a predicate would mutate the base style mid-revision, so
 * writes are refused.
 */
private fun predicateLayerHandle(style: StyleBinding, id: String, type: String): LayerHandle {
  val identity = style.identity.layers.get(id)
  return LayerHandle(
    id = id,
    type = type,
    style = style,
    isCurrentResource = { style.identity.layers.isCurrent(id, identity) },
    operations =
      object : StyleHandleOperationGuard {
        override fun <T> run(action: () -> T): T = action()

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

/** IDs touched by actual insertions, removals, or moves during an applied revision. */
internal class StyleResourceChanges(val identity: StyleIdentity? = null) {
  val sources = linkedSetOf<String>()
  val layers = linkedSetOf<String>()
  var layerOrder: List<String>? = null
}
