package org.maplibre.compose.style

import org.maplibre.compose.layers.Anchor

/** Reconciles complete desired revisions into one loaded base-style generation. */
internal class StyleReconciler {
  private var binding: StyleBinding? = null
  private val sources = linkedMapOf<String, AppliedSource>()
  private val layers = linkedMapOf<String, AppliedLayer>()
  private val images = linkedMapOf<String, StyleImageDefinition>()
  private val replacedLayers = mutableMapOf<Anchor.Replace, LayerDefinition>()

  /**
   * The engine's layer order, bottom to top, as this reconciler's mutations leave it. Reading the
   * engine's order is a cross-thread round trip on native engines, so it is tracked locally and
   * re-read only when it may have drifted: after a binding change or a failed revision.
   */
  private var knownLayerIds: MutableList<String>? = null

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
    val desiredLayers = revision.layers.associateBy { it.definition.id }

    layers.values.toList().forEach { applied ->
      val desired = desiredLayers[applied.definition.id]
      if (
        desired == null ||
          desired.anchor != applied.anchor ||
          desired.definition.type != applied.definition.type ||
          desired.definition.sourceId != applied.definition.sourceId ||
          desired.definition.value["source-layer"] != applied.definition.value["source-layer"] ||
          desired.definition.sourceId in replacedSourceIds
      ) {
        removeLayer(style, applied, changes)
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

    revision.layers
      .groupByTo(linkedMapOf()) { it.anchor }
      .forEach { (anchor, group) ->
        var previousId: String? = null
        group.forEachIndexed { index, desired ->
          val id = desired.definition.id
          val nextDesiredId = group.getOrNull(index + 1)?.definition?.id
          var applied = layers[id]
          if (applied == null) {
            val before = beforeLayerId(layerIds(style), anchor, previousId, id)
            if (anchor is Anchor.Replace && anchor !in replacedLayers) {
              val replaced =
                requireNotNull(style.getLayer(anchor.layerId)) {
                  "Layer ID '${anchor.layerId}' not found in base style"
                }
              replacedLayers[anchor] = replaced.definition()
            }
            applied =
              AppliedLayer(
                definition = desired.definition,
                anchor = anchor,
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
            if (anchor is Anchor.Replace && group.first() === desired) {
              style.identity.layers.remove(anchor.layerId)
              changes.layers.add(anchor.layerId)
              style.removeLayer(anchor.layerId)
              layerIds(style).remove(anchor.layerId)
            }
          } else {
            applied.installation.update(desired.definition, revision.animatorDurationScale)
            applied.definition = desired.definition
            if (shouldMoveLayer(layerIds(style), anchor, previousId, id, nextDesiredId)) {
              val before = beforeLayerId(layerIds(style), anchor, previousId, id)
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
    replacedLayers.clear()
    knownLayerIds = null
  }

  private fun layerIds(style: StyleBinding): MutableList<String> =
    knownLayerIds ?: style.layerIds().toMutableList().also { knownLayerIds = it }

  /** Places [id] directly below [beforeLayerId], or on top when that is empty. */
  private fun MutableList<String>.insertBelow(id: String, beforeLayerId: String) {
    if (beforeLayerId.isEmpty()) {
      add(id)
    } else {
      val index = indexOf(beforeLayerId)
      require(index >= 0) { "Layer ID '$beforeLayerId' not found in style" }
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

  private fun removeLayer(
    style: StyleBinding,
    applied: AppliedLayer,
    changes: StyleResourceChanges,
  ) {
    changes.layers.add(applied.definition.id)
    val anchor = applied.anchor
    val isLastReplacement =
      anchor is Anchor.Replace && layers.values.count { it.anchor == anchor } == 1
    if (isLastReplacement) {
      val original = requireNotNull(replacedLayers.remove(anchor))
      changes.layers.add(original.id)
      style.addLayer(original, beforeLayerId = applied.definition.id)
      knownLayerIds?.insertBelow(anchor.layerId, applied.definition.id)
    }
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
    anchor: Anchor,
    previousId: String?,
    id: String,
    nextDesiredId: String?,
  ): Boolean {
    if (previousId != null) return ids.idAbove(previousId) != id
    if (nextDesiredId != null) return ids.positionBefore(id) != nextDesiredId
    val before = beforeLayerId(ids, anchor, previousId = null, desiredId = id)
    return before != id && ids.positionBefore(id) != before
  }

  private fun beforeLayerId(
    ids: List<String>,
    anchor: Anchor,
    previousId: String?,
    desiredId: String? = null,
  ): String {
    if (previousId != null) return ids.idAbove(previousId)
    if (anchor is Anchor.Replace && anchor in replacedLayers) {
      val firstReplacement = ids.firstOrNull { layers[it]?.anchor == anchor }
      if (firstReplacement != null && firstReplacement != desiredId) return firstReplacement
      if (desiredId != null && desiredId in ids) {
        return ids.positionBefore(desiredId)
      }
    }
    return when (anchor) {
      is Anchor.Top -> ""
      is Anchor.Bottom -> ids.firstOrNull().orEmpty()
      is Anchor.Above -> ids.idAbove(anchor.layerId)
      is Anchor.Below -> anchor.layerId
      is Anchor.Replace -> ids.idAbove(anchor.layerId)
    }
  }

  private fun List<String>.idAbove(id: String): String {
    val index = indexOf(id)
    require(index >= 0) { "Layer ID '$id' not found in base style" }
    return getOrNull(index + 1).orEmpty()
  }

  private fun List<String>.positionBefore(id: String): String {
    val index = indexOf(id)
    require(index >= 0) { "Layer ID '$id' not found in style" }
    return getOrNull(index + 1).orEmpty()
  }

  private class AppliedSource(
    var definition: SourceDefinition,
    val installation: SourceInstallation,
  )

  private class AppliedLayer(
    var definition: LayerDefinition,
    val anchor: Anchor,
    val installation: LayerInstallation,
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
