package org.maplibre.compose.style

import org.maplibre.compose.layers.Anchor

/** Reconciles complete desired revisions into one loaded base-style generation. */
internal class StyleReconciler {
  private var binding: StyleBinding? = null
  private val sources = linkedMapOf<String, AppliedSource>()
  private val layers = linkedMapOf<String, AppliedLayer>()
  private val images = linkedMapOf<String, StyleImageDefinition>()
  private val replacedLayers = mutableMapOf<Anchor.Replace, LayerDefinition>()

  suspend fun apply(style: StyleBinding, revision: DesiredStyleRevision) {
    style.requireCurrent()
    if (binding !== style) reset(style)

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
        removeLayer(style, applied)
      }
    }

    sources.values.toList().forEach { applied ->
      val desired = desiredSources[applied.definition.id]
      when {
        desired == null -> removeSource(applied)
        applied.definition.canUpdateTo(desired) -> {
          applied.handle.update(desired)
          applied.definition = desired
        }
        else -> {
          removeSource(applied)
          addSource(style, desired)
        }
      }
    }
    revision.sources.forEach { definition ->
      if (definition.id !in sources) addSource(style, definition)
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
            val before = beforeLayerId(style, anchor, previousId, id)
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
                handle = LayerHandle(style, desired.definition, before),
              )
            layers[id] = applied
            if (anchor is Anchor.Replace && group.first() === desired) {
              style.removeLayer(anchor.layerId)
            }
          } else {
            applied.handle.update(desired.definition)
            applied.definition = desired.definition
            if (shouldMoveLayer(style, anchor, previousId, id, nextDesiredId)) {
              val before = beforeLayerId(style, anchor, previousId, id)
              if (before != id) applied.handle.move(before)
            }
          }
          previousId = id
        }
      }
  }

  private fun reset(style: StyleBinding) {
    binding = style
    sources.clear()
    layers.clear()
    images.clear()
    replacedLayers.clear()
  }

  private fun addSource(style: StyleBinding, definition: SourceDefinition) {
    sources[definition.id] = AppliedSource(definition, SourceHandle(style, definition))
  }

  private fun removeSource(applied: AppliedSource) {
    applied.handle.remove()
    sources.remove(applied.definition.id)
  }

  private fun removeLayer(style: StyleBinding, applied: AppliedLayer) {
    val anchor = applied.anchor
    val isLastReplacement =
      anchor is Anchor.Replace && layers.values.count { it.anchor == anchor } == 1
    if (isLastReplacement) {
      val original = requireNotNull(replacedLayers.remove(anchor))
      style.addLayer(original, beforeLayerId = applied.definition.id)
    }
    applied.handle.remove()
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
    style: StyleBinding,
    anchor: Anchor,
    previousId: String?,
    id: String,
    nextDesiredId: String?,
  ): Boolean {
    val ids = style.layerIds()
    if (previousId != null) return ids.idAbove(previousId) != id
    if (nextDesiredId != null) return ids.positionBefore(id) != nextDesiredId
    val before = beforeLayerId(style, anchor, previousId = null, desiredId = id)
    return before != id && ids.positionBefore(id) != before
  }

  private fun beforeLayerId(
    style: StyleBinding,
    anchor: Anchor,
    previousId: String?,
    desiredId: String? = null,
  ): String {
    if (previousId != null) return style.layerIds().idAbove(previousId)
    if (anchor is Anchor.Replace && anchor in replacedLayers) {
      val firstReplacement = style.layerIds().firstOrNull { layers[it]?.anchor == anchor }
      if (firstReplacement != null && firstReplacement != desiredId) return firstReplacement
      if (desiredId != null && desiredId in style.layerIds()) {
        return style.layerIds().positionBefore(desiredId)
      }
    }
    return when (anchor) {
      is Anchor.Top -> ""
      is Anchor.Bottom -> style.layerIds().firstOrNull().orEmpty()
      is Anchor.Above -> style.layerIds().idAbove(anchor.layerId)
      is Anchor.Below -> anchor.layerId
      is Anchor.Replace -> style.layerIds().idAbove(anchor.layerId)
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

  private fun SourceDefinition.canUpdateTo(next: SourceDefinition): Boolean =
    when {
      this is SourceDefinition.GeoJson && next is SourceDefinition.GeoJson ->
        options == next.options
      this is SourceDefinition.Image && next is SourceDefinition.Image -> true
      this is SourceDefinition.CustomGeometry && next is SourceDefinition.CustomGeometry ->
        options == next.options
      this is SourceDefinition.CustomVector && next is SourceDefinition.CustomVector ->
        options == next.options
      else -> this == next
    }

  private class AppliedSource(
    var definition: SourceDefinition,
    val handle: SourceHandle,
  )

  private class AppliedLayer(
    var definition: LayerDefinition,
    val anchor: Anchor,
    val handle: LayerHandle,
  )
}
