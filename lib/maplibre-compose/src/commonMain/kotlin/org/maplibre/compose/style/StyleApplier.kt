package org.maplibre.compose.style

import co.touchlab.kermit.Logger
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.ImageSource
import org.maplibre.compose.sources.Source

/**
 * Diffs a [DesiredStyleRevision] against the last applied snapshot and mutates [StyleBinding] to
 * match. Bookkeeping updates only after each engine operation succeeds, so a throw resumes from the
 * true engine state on the next apply.
 */
internal class StyleApplier {

  private var syncedBinding: StyleBinding? = null
  private var appliedSources = mutableSetOf<Source>()
  private val appliedGeoJson = mutableMapOf<String, GeoJsonData>()
  private val appliedLayers = LinkedHashMap<Anchor, MutableList<LayerNode<*>>>()
  private val replacedLayers = mutableMapOf<Anchor.Replace, Layer>()
  private val pendingReplaceRemovals = mutableMapOf<Anchor.Replace, Layer>()
  private val reportedUnresolvableAnchors = mutableSetOf<Anchor>()

  internal fun apply(
    binding: StyleBinding,
    revision: DesiredStyleRevision,
    baseStyle: BaseStyleSnapshot,
    imageManager: ImageManager,
    refreshSource: (String) -> Unit,
    reportError: (StyleError) -> Unit,
    logger: Logger?,
  ) {
    if (syncedBinding !== binding) {
      appliedSources.clear()
      appliedGeoJson.clear()
      appliedLayers.clear()
      replacedLayers.clear()
      pendingReplaceRemovals.clear()
      reportedUnresolvableAnchors.clear()
      imageManager.ensureAttached()
      syncedBinding = binding
    }
    if (!binding.isLoaded) return

    val desiredLayers = revision.layers
    retryPendingReplaceRemovals(binding, desiredLayers.mapTo(hashSetOf()) { it.anchor }, logger)
    removeUndesiredLayers(binding, desiredLayers, logger)
    syncSources(binding, revision.sources, refreshSource, logger)

    val desiredByAnchor = LinkedHashMap<Anchor, MutableList<LayerNode<*>>>()
    desiredLayers.forEach { desiredByAnchor.getOrPut(it.anchor) { mutableListOf() }.add(it) }
    desiredByAnchor.forEach { (anchor, group) ->
      syncAnchorGroup(binding, anchor, group, baseStyle, reportError, logger)
    }
    desiredLayers.forEach { it.layer.applyProperties(binding) }
  }

  private fun retryPendingReplaceRemovals(
    binding: StyleBinding,
    desiredAnchors: Set<Anchor>,
    logger: Logger?,
  ) {
    pendingReplaceRemovals.entries.toList().forEach { (anchor, original) ->
      if (anchor !in desiredAnchors) {
        pendingReplaceRemovals.remove(anchor)
        return@forEach
      }
      logger?.i { "Retrying removal of replaced layer ${original.id}" }
      binding.removeLayer(original)
      pendingReplaceRemovals.remove(anchor)
      replacedLayers[anchor] = original
    }
  }

  private fun removeUndesiredLayers(
    binding: StyleBinding,
    desiredLayers: List<LayerNode<*>>,
    logger: Logger?,
  ) {
    val desired = desiredLayers.toHashSet()
    val anchors = appliedLayers.entries.iterator()
    while (anchors.hasNext()) {
      val (anchor, group) = anchors.next()
      group
        .filter { it !in desired }
        .forEach { node ->
          if (anchor is Anchor.Replace && group.size == 1) {
            pendingReplaceRemovals.remove(anchor)
            replacedLayers[anchor]?.let { original ->
              logger?.i { "Restoring layer ${anchor.layerId}" }
              binding.addLayerBelow(node.layer.id, original)
              replacedLayers.remove(anchor)
            }
          }
          logger?.i { "Removing layer ${node.layer.id}" }
          binding.removeLayer(node.layer)
          group.remove(node)
        }
      if (group.isEmpty()) anchors.remove()
    }
  }

  private fun syncSources(
    binding: StyleBinding,
    desired: Set<Source>,
    refreshSource: (String) -> Unit,
    logger: Logger?,
  ) {
    appliedSources
      .filter { it !in desired }
      .forEach { source ->
        logger?.i { "Removing source ${source.id}" }
        binding.removeSource(source)
        appliedSources.remove(source)
        appliedGeoJson.remove(source.id)
        refreshSource(source.id)
      }
    desired.forEach { source ->
      if (source !in appliedSources) {
        logger?.i { "Adding source ${source.id}" }
        binding.addSource(source)
        appliedSources.add(source)
        if (source is GeoJsonSource) appliedGeoJson[source.id] = source.data
        refreshSource(source.id)
      } else {
        applySourcePayload(binding, source)
      }
    }
  }

  private fun applySourcePayload(binding: StyleBinding, source: Source) {
    when (source) {
      is GeoJsonSource -> {
        val data = source.data
        if (appliedGeoJson[source.id] == data) return
        if (data is GeoJsonData.Uri) {
          binding.setGeoJsonSourceUrl(source.id, data.uri)
        } else {
          binding.prepareGeoJson(data, source.options).use {
            binding.setGeoJsonSourceData(source.id, it)
          }
        }
        appliedGeoJson[source.id] = data
      }
      is ImageSource -> source.applyPayload(binding)
      else -> Unit
    }
  }

  private fun syncAnchorGroup(
    binding: StyleBinding,
    anchor: Anchor,
    desired: List<LayerNode<*>>,
    baseStyle: BaseStyleSnapshot,
    reportError: (StyleError) -> Unit,
    logger: Logger?,
  ) {
    val applied = appliedLayers[anchor]
    if (applied.isNullOrEmpty()) {
      if (!anchor.isResolvable(baseStyle)) {
        logger?.w { "Anchor $anchor names no layer in the base style; deferring its layers" }
        if (binding.isLoaded && reportedUnresolvableAnchors.add(anchor)) {
          val message =
            "Anchor $anchor names no layer '${anchor.anchorLayerId()}' in the loaded base style; " +
              "its layers are deferred"
          reportError(StyleError(message, IllegalStateException(message)))
        }
        return
      }
      initializeAnchor(binding, anchor, desired, logger)
      return
    }

    val appliedIndex = HashMap<LayerNode<*>, Int>(applied.size)
    applied.forEachIndexed { index, node -> appliedIndex[node] = index }
    val stable = HashSet<LayerNode<*>>()
    var previousIndex = -1
    desired.forEach { node ->
      val index = appliedIndex[node] ?: return@forEach
      if (index > previousIndex) {
        stable += node
        previousIndex = index
      }
    }

    var previousId: String? = null
    desired.forEachIndexed { position, node ->
      val layer = node.layer
      when {
        node in stable -> Unit
        node in appliedIndex -> {
          logger?.i { "Moving layer ${layer.id} above $previousId" }
          moveLayerAbove(binding, checkNotNull(previousId), layer)
          applied.remove(node)
          applied.add(applied.indexOfFirst { it.layer.id == previousId } + 1, node)
        }
        previousId != null -> {
          logger?.i { "Adding layer ${layer.id} above $previousId" }
          binding.addLayerAbove(checkNotNull(previousId), layer)
          applied.add(applied.indexOfFirst { it.layer.id == previousId } + 1, node)
        }
        else -> {
          val head = desired.drop(position + 1).first { it in stable }
          logger?.i { "Adding layer ${layer.id} below ${head.layer.id}" }
          binding.addLayerBelow(head.layer.id, layer)
          applied.add(applied.indexOf(head), node)
        }
      }
      previousId = layer.id
    }
    appliedLayers[anchor] = desired.toMutableList()
  }

  private fun initializeAnchor(
    binding: StyleBinding,
    anchor: Anchor,
    desired: List<LayerNode<*>>,
    logger: Logger?,
  ) {
    val applied = appliedLayers.getOrPut(anchor) { mutableListOf() }
    val first = desired.first()
    logger?.i { "Initializing anchor $anchor with layer ${first.layer.id}" }
    when (anchor) {
      is Anchor.Top -> binding.addLayer(first.layer)
      is Anchor.Bottom -> binding.addLayerAt(0, first.layer)
      is Anchor.Above -> binding.addLayerAbove(anchor.layerId, first.layer)
      is Anchor.Below -> binding.addLayerBelow(anchor.layerId, first.layer)
      is Anchor.Replace -> {
        val layerToReplace = checkNotNull(binding.getLayer(anchor.layerId))
        binding.addLayerAbove(layerToReplace.id, first.layer)
        applied += first
        logger?.i { "Replacing layer ${layerToReplace.id} with ${first.layer.id}" }
        pendingReplaceRemovals[anchor] = layerToReplace
        binding.removeLayer(layerToReplace)
        pendingReplaceRemovals.remove(anchor)
        replacedLayers[anchor] = layerToReplace
      }
    }
    if (applied.isEmpty()) applied += first
    var previous = first
    desired.drop(1).forEach { node ->
      logger?.i { "Adding layer ${node.layer.id} above ${previous.layer.id}" }
      binding.addLayerAbove(previous.layer.id, node.layer)
      applied += node
      previous = node
    }
  }

  private fun moveLayerAbove(binding: StyleBinding, targetId: String, layer: Layer) {
    val ids = binding.layerIds() ?: return
    val above = ids.getOrNull(ids.indexOf(targetId) + 1).orEmpty()
    if (above == layer.id) return
    binding.moveLayer(layer.id, above)
  }
}

private fun Anchor.anchorLayerId(): String? =
  when (this) {
    is Anchor.Above -> layerId
    is Anchor.Below -> layerId
    is Anchor.Replace -> layerId
    else -> null
  }

private fun Anchor.isResolvable(baseStyle: BaseStyleSnapshot): Boolean {
  val layerId = anchorLayerId() ?: return true
  return layerId in baseStyle.layerIds
}
