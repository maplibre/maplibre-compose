package org.maplibre.compose.style

import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.LayerSummary

/** Reconciles complete desired revisions into one loaded base-style generation. */
internal class StyleReconciler {
  private var fontScale: Float? = null
  private var groundScale: Float? = null

  // Commit state is accessed only by the serialized apply calls.
  private var binding: StyleBinding? = null
  private val sources = linkedMapOf<String, SourceInstallation>()
  private val layers = linkedMapOf<String, AppliedLayer>()

  /**
   * The image this reconciler wrote for each ID it may have installed, or null while a write has
   * not been accepted and the engine may hold either image.
   */
  private val images = linkedMapOf<String, StyleImageDefinition?>()

  /** Resolve application anchor predicates on the composition's caller, before owner work. */
  fun prepare(style: StyleBinding, revision: StyleSnapshot): PreparedRevision {
    style.requireCurrent()
    val placements = hashMapOf<Anchor, Placement>()
    val layers =
      revision.layers.map { desired ->
        PlacedLayer(
          desired,
          placements.getOrPut(desired.anchor) {
            placement(desired.anchor, style.baseLayers)
          },
        )
      }
    return PreparedRevision(style.identity, revision, layers)
  }

  fun apply(style: StyleBinding, revision: StyleSnapshot) = apply(style, prepare(style, revision))

  /** Serialized on one executor; native callers use the map owner thread. */
  fun apply(style: StyleBinding, prepared: PreparedRevision) {
    style.requireCurrent(prepared.identity)
    if (binding !== style) reset(style)
    applyRevision(style, prepared)
  }

  private fun applyRevision(style: StyleBinding, prepared: PreparedRevision) {
    val layerIds = style.layerIds().toMutableList()
    val revision = prepared.revision
    revision.fontScale?.let { next ->
      if (fontScale != next) {
        style.setGlobalStateProperty(FontScaleGlobalState, JsonPrimitive(next))
        fontScale = next
      }
    }
    revision.groundScale?.let { next ->
      if (groundScale != next) {
        style.setGlobalStateProperty(GroundScaleGlobalState, JsonPrimitive(next))
        groundScale = next
      }
    }
    val desiredSources = revision.sources.associateBy(SourceDefinition::id)
    val replacedSourceIds =
      sources.mapNotNullTo(mutableSetOf()) { (id, applied) ->
        desiredSources[id]?.takeIf { !applied.definition.canUpdateTo(it) }?.let { id }
      }
    val placedLayers = prepared.layers
    val desiredLayers = placedLayers.associateBy { it.definition.id }

    layers.values.toList().forEach { applied ->
      val desired = desiredLayers[applied.definition.id]
      if (
        desired == null ||
          desired.placement != applied.placement ||
          desired.definition.type != applied.definition.type ||
          desired.definition.sourceId != applied.definition.sourceId ||
          !desired.definition.hasSameConstructionProperties(applied.definition) ||
          desired.definition.sourceId in replacedSourceIds
      ) {
        removeLayer(applied)
        layerIds.remove(applied.definition.id)
      }
    }

    sources.values.toList().forEach { applied ->
      val desired = desiredSources[applied.definition.id]
      when {
        desired == null -> removeSource(applied)
        applied.definition.canUpdateTo(desired) -> applied.update(desired)
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

    placedLayers
      .groupByTo(linkedMapOf()) { it.placement }
      .forEach { (placement, group) ->
        var previousId: String? = null
        group.forEachIndexed { index, desired ->
          val id = desired.definition.id
          val nextDesiredId = group.getOrNull(index + 1)?.definition?.id
          var applied = layers[id]
          if (applied == null) {
            val before = beforeLayerId(layerIds, placement, previousId)
            applied =
              AppliedLayer(
                placement = placement,
                installation =
                  LayerInstallation(
                    style,
                    desired.definition,
                    before,
                    revision.animatorDurationScale,
                  ),
              )
            layers[id] = applied
            layerIds.insertBelow(id, before)
          } else {
            applied.installation.update(desired.definition, revision.animatorDurationScale)
            if (shouldMoveLayer(layerIds, placement, previousId, id, nextDesiredId)) {
              val before = beforeLayerId(layerIds, placement, previousId)
              if (before != id) {
                applied.installation.move(before)
                layerIds.also {
                  it.remove(id)
                  it.insertBelow(id, before)
                }
              }
            }
          }
          previousId = id
        }
      }
  }

  private fun reset(style: StyleBinding) {
    binding = style
    fontScale = null
    groundScale = null
    sources.clear()
    layers.clear()
    images.clear()
  }

  /** Resolves [anchor] against the base-style layers of the bound generation. */
  private fun placement(anchor: Anchor, baseLayers: List<LayerSummary>): Placement =
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
  ) {
    sources[definition.id] = SourceInstallation(style, definition)
  }

  private fun removeSource(applied: SourceInstallation) {
    applied.remove()
    sources.remove(applied.id)
  }

  private fun removeLayer(applied: AppliedLayer) {
    applied.installation.remove()
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

  private class AppliedLayer(
    val placement: Placement,
    val installation: LayerInstallation,
  ) {
    val definition: LayerDefinition
      get() = installation.definition
  }

  internal class PreparedRevision(
    val identity: StyleIdentity,
    val revision: StyleSnapshot,
    val layers: List<PlacedLayer>,
  )

  internal class PlacedLayer(desired: StyleSnapshot.Layer, val placement: Placement) {
    val definition: LayerDefinition = desired.definition
  }

  /**
   * An anchor resolved against one base-style generation. Layers with equal placements form one
   * group in style-content order, so two anchors that resolve to the same position never move each
   * other's layers.
   */
  internal sealed interface Placement {
    data object Top : Placement

    data object Bottom : Placement

    /** Directly under the base-style layer [layerId]. */
    data class Below(val layerId: String) : Placement
  }
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
