package org.maplibre.compose.style

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.LayerSummary

/**
 * Reconciles complete desired revisions into one loaded base-style generation.
 *
 * Each structural write is recorded in the binding's [StyleContents] when it is submitted. A source
 * or layer the engine rejects is dropped with a warning and skipped until its definition changes;
 * [settle] reports the rejection so the caller applies the latest revision again, which installs
 * what the rejection had blocked.
 */
internal class StyleReconciler {
  private var fontScale: Float? = null

  // Reconciliation state is confined to main.
  private var binding: StyleBinding? = null
  private val sources = linkedMapOf<String, SourceInstallation>()
  private val layers = linkedMapOf<String, AppliedLayer>()

  /**
   * The image this reconciler wrote for each ID it may have installed, or null after the engine
   * rejected a write and may hold either image.
   */
  private val images = linkedMapOf<String, StyleImageDefinition?>()

  /**
   * Sources whose removal the engine refused while a replacement with the same ID was pending. A
   * rejected replacement leaves the engine with the refused source, which is tracked again.
   */
  private val retainedSources =
    mutableMapOf<String, Pair<SourceInstallation, StyleContents.SourceEntry>>()

  /** Source definitions the engine rejected, skipped until the revision changes them. */
  private val rejectedSources = mutableMapOf<String, SourceDefinition>()

  /**
   * Layer additions the engine rejected. A revision skips each until something it depended on
   * changes: its definition, the layer it is placed below, or whether its source exists.
   */
  private val rejectedLayers = mutableMapOf<String, LayerRejection>()

  /**
   * Structural writes whose outcome [settle] has not handled, each with its rejection handler. A
   * browser write completes inline, so its handler waits until the revision has been applied.
   */
  private val pending = mutableListOf<Pair<StyleCommand, (StyleMutationException) -> Unit>>()

  /** Whether the engine rejected a source, layer, or move since the last [settle]. */
  private var rejected = false

  /** Resolves application anchor predicates on the composition's caller, before any write. */
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

  /** Submits the revision's writes synchronously on main. */
  fun apply(style: StyleBinding, prepared: PreparedRevision) {
    style.requireCurrent(prepared.identity)
    if (binding !== style) reset(style)
    applyRevision(style, prepared)
  }

  /**
   * Waits for the outcome of every structural write applied so far, and handles each rejection.
   *
   * @return true if the engine rejected a source, layer, or move. The layer order is then read back
   *   from the engine, and the caller applies the latest revision again.
   */
  suspend fun settle(style: StyleBinding): Boolean {
    while (pending.isNotEmpty()) {
      val outcomes = pending.toList()
      pending.clear()
      outcomes.forEach { (command, onRejected) ->
        try {
          command.await()
        } catch (error: StyleMutationException) {
          onRejected(error)
        } catch (error: CancellationException) {
          // The command was cancelled, such as by its map closing, unless this caller was.
          currentCoroutineContext().ensureActive()
        }
      }
    }
    if (!rejected) return false
    rejected = false
    if (binding !== style || !style.isLoaded) return false
    // A rejected move or a write placed against a rejected layer leaves the engine's order unknown.
    while (true) {
      val version = style.contents.layerOrderVersion
      val engineLayers = style.layerSummaries()
      if (binding !== style || !style.isLoaded) return false
      // A layer write submitted during the read makes it stale.
      if (version != style.contents.layerOrderVersion) continue
      style.contents.replaceLayers(engineLayers)
      val engineIds = engineLayers.mapTo(hashSetOf()) { it.id }
      layers.keys.retainAll(engineIds)
      return true
    }
  }

  private fun applyRevision(style: StyleBinding, prepared: PreparedRevision) {
    val revision = prepared.revision
    val contents = style.contents
    revision.fontScale?.let { next ->
      if (fontScale != next) {
        style.setGlobalStateProperty(FontScaleGlobalState, JsonPrimitive(next))
        fontScale = next
      }
    }
    val desiredSources = revision.sources.associateBy(SourceDefinition::id)
    rejectedSources.entries.removeAll { (id, rejected) -> desiredSources[id] != rejected }
    val replacedSourceIds =
      sources.mapNotNullTo(mutableSetOf()) { (id, applied) ->
        desiredSources[id]?.takeIf { !applied.definition.canUpdateTo(it) }?.let { id }
      }
    val placedLayers = prepared.layers
    val desiredLayers = placedLayers.associateBy { it.definition.id }
    rejectedLayers.entries.removeAll { (id, rejected) ->
      desiredLayers[id]?.definition != rejected.definition
    }

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
        removeLayer(style, applied)
      }
    }

    sources.values.toList().forEach { applied ->
      val desired = desiredSources[applied.definition.id]
      when {
        desired == null -> removeSource(style, applied)
        applied.definition.canUpdateTo(desired) -> applied.update(desired)
        else -> {
          removeSource(style, applied)
          addSource(style, desired)
        }
      }
    }
    revision.sources.forEach { definition ->
      if (definition.id !in sources && definition.id !in rejectedSources) {
        addSource(style, definition)
      }
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
            // A layer over a rejected source waits for the source.
            if (desired.definition.sourceId in rejectedSources) return@forEachIndexed
            val before = beforeLayerId(contents.layerIds(), placement, previousId)
            if (rejectedLayers[id] == layerRejection(style, desired.definition, before)) {
              return@forEachIndexed
            }
            applied =
              addLayer(style, desired, placement, before, revision.animatorDurationScale)
                ?: return@forEachIndexed
          } else {
            applied.installation.update(desired.definition, revision.animatorDurationScale)
            val ids = contents.layerIds()
            if (shouldMoveLayer(ids, placement, previousId, id, nextDesiredId)) {
              val before = beforeLayerId(ids, placement, previousId)
              if (before != id) moveLayer(style, applied, before)
            }
          }
          previousId = id
        }
      }
  }

  private fun reset(style: StyleBinding) {
    binding = style
    fontScale = null
    sources.clear()
    layers.clear()
    images.clear()
    rejectedSources.clear()
    retainedSources.clear()
    rejectedLayers.clear()
    pending.clear()
    rejected = false
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

  private fun addSource(style: StyleBinding, definition: SourceDefinition) {
    // The engine would reject a second source with the ID, so the existing one is kept as it is.
    if (definition.id in style.contents.sources) {
      rejectedSources[definition.id] = definition
      style.logger?.w {
        "Source '${definition.id}' was not added: the style already has a source with this ID"
      }
      return
    }
    val installation = SourceInstallation(style, definition)
    sources[definition.id] = installation
    style.contents.addSource(definition)
    track(installation.added) { error ->
      if (binding !== style || sources[definition.id] !== installation) return@track
      sources.remove(definition.id)
      style.contents.removeSource(definition.id)
      style.identity.sources.remove(definition.id)
      retainedSources.remove(definition.id)?.let { (retained, entry) ->
        sources[definition.id] = retained
        style.contents.restoreSource(definition.id, entry)
      }
      rejectedSources[definition.id] = definition
      rejected = true
      style.logger?.w(error) {
        "Source '${definition.id}' was not added, and layers over it wait until its definition " +
          "changes: ${error.message}"
      }
    }
  }

  private fun removeSource(style: StyleBinding, applied: SourceInstallation) {
    sources.remove(applied.id)
    val entry = style.contents.removeSource(applied.id)
    track(applied.remove()) { error ->
      style.logger?.w(error) { "Source '${applied.id}' was not removed: ${error.message}" }
      // The engine kept the source, such as for a layer added outside the style API. A pending
      // replacement with the same ID will be rejected, and the source is tracked again then.
      if (binding !== style || entry == null) return@track
      if (applied.id in sources || applied.id in style.contents.sources) {
        retainedSources[applied.id] = applied to entry
        return@track
      }
      sources[applied.id] = applied
      style.contents.restoreSource(applied.id, entry)
    }
  }

  private fun addLayer(
    style: StyleBinding,
    desired: PlacedLayer,
    placement: Placement,
    before: String,
    animatorDurationScale: Float,
  ): AppliedLayer? {
    val definition = desired.definition
    // The engine would reject a second layer with the ID, so the existing one is kept as it is.
    if (style.contents.hasLayer(definition.id)) {
      rejectedLayers[definition.id] = layerRejection(style, definition, before)
      style.logger?.w {
        "Layer '${definition.id}' was not added: the style already has a layer with this ID"
      }
      return null
    }
    val applied =
      AppliedLayer(
        placement = placement,
        installation = LayerInstallation(style, definition, before, animatorDurationScale),
      )
    layers[definition.id] = applied
    style.contents.insertLayer(definition.summary(), before)
    track(applied.installation.added) { error ->
      if (binding !== style || layers[definition.id] !== applied) return@track
      layers.remove(definition.id)
      style.contents.removeLayer(definition.id)
      style.identity.layers.remove(definition.id)
      rejected = true
      val rejection = layerRejection(style, definition, before)
      rejectedLayers[definition.id] = rejection
      // A layer placed against a rejected layer, or over a missing source, failed only because of
      // that; it is placed again once the layer is gone or the source exists.
      if ((before.isNotEmpty() && !style.contents.hasLayer(before)) || !rejection.sourceExists) {
        return@track
      }
      style.logger?.w(error) {
        "${applied.installation.describe()} was not added, and stays out of the style until its " +
          "definition changes: ${error.message}. Layer JSON: ${definition.value}"
      }
    }
    return applied
  }

  private fun removeLayer(style: StyleBinding, applied: AppliedLayer) {
    layers.remove(applied.definition.id)
    style.contents.removeLayer(applied.definition.id)
    track(applied.installation.remove()) { if (binding === style) rejected = true }
  }

  private fun moveLayer(style: StyleBinding, applied: AppliedLayer, before: String) {
    style.contents.moveLayer(applied.definition.id, before)
    track(applied.installation.move(before)) { if (binding === style) rejected = true }
  }

  /** Records [command] for [settle], which runs [onRejected] if the engine rejects it. */
  private fun track(command: StyleCommand, onRejected: (StyleMutationException) -> Unit) {
    pending += command to onRejected
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
      // Replaced in place, so no frame renders without the image. A rejected write leaves the
      // engine with either image, so the next revision writes it again, or removes it if the
      // revision drops the ID.
      images[id] = definition
      style.identity.images.remove(id)
      style.setImage(definition).onRejected { error ->
        if (binding !== style || images[id] !== definition) return@onRejected
        images[id] = null
        style.reportRejectedWrite("Image '$id'", null, error)
      }
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

  private fun layerRejection(style: StyleBinding, definition: LayerDefinition, before: String) =
    LayerRejection(
      definition,
      before,
      sourceExists = definition.sourceId?.let { it in style.contents.sources } ?: true,
    )

  private data class LayerRejection(
    val definition: LayerDefinition,
    val before: String,
    val sourceExists: Boolean,
  )

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
