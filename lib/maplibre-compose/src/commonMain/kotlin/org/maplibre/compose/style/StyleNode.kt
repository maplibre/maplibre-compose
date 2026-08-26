package org.maplibre.compose.style

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.Source

/**
 * The root of the style composition. The composition's callbacks record desired state only; [sync]
 * diffs that against an in-memory snapshot of what was applied and issues the minimal engine
 * operations. The node outlives any one style: re-pointing [binding] resets the applied snapshot,
 * and the next sync reapplies the whole desired state to the new style.
 */
internal class StyleNode(binding: StyleBinding, internal var logger: Logger?) : MapNode() {

  /** Snapshot-backed so content that reads it recomposes when the style swaps. */
  internal var binding: StyleBinding by mutableStateOf(binding)

  internal val sourceManager = SourceManager(this)
  internal val imageManager = ImageManager(this)

  /** Set by the host; asks it to run a sync when desired state changes outside a frame. */
  internal var requestSync: (() -> Unit)? = null

  /** The binding the applied snapshot below belongs to; a mismatch with [binding] resets it. */
  private var syncedBinding: StyleBinding? = null

  private var baseLayersFor: StyleBinding? = null
  private var baseLayerIds: Set<String> = emptySet()

  private val appliedSources = mutableSetOf<Source>()
  private val appliedLayers = LinkedHashMap<Anchor, MutableList<LayerNode<*>>>()
  private val replacedLayers = mutableMapOf<Anchor.Replace, Layer>()

  override fun allowsChild(node: MapNode) = node is LayerNode<*>

  override fun onChildInserted(index: Int, node: MapNode) {
    node as LayerNode<*>
    require(node.layer.id !in baseLayerIds()) {
      "Layer ID '${node.layer.id}' already exists in base style"
    }
    logger?.i { "Recorded layer ${node.layer.id} at anchor ${node.anchor}, index $index" }
  }

  /** Valid only while the style holds no user content; [applyChanges] captures it before adding. */
  private fun baseLayerIds(): Set<String> {
    val binding = binding
    if (baseLayersFor !== binding) {
      baseLayerIds = binding.getLayers().mapTo(mutableSetOf()) { it.id }
      baseLayersFor = binding
    }
    return baseLayerIds
  }

  /** Diffs the desired state against the applied snapshot and mutates the engine to match. */
  internal fun applyChanges() {
    val binding = binding
    if (syncedBinding !== binding) {
      appliedSources.clear()
      appliedLayers.clear()
      replacedLayers.clear()
      // The base snapshots must capture the pristine style before this sync mutates it.
      baseLayersFor = null
      baseLayerIds()
      sourceManager.captureBaseSources()
      imageManager.ensureAttached()
      syncedBinding = binding
    }
    if (!binding.isLoaded) return

    val desiredLayers = children.filterIsInstance<LayerNode<*>>()
    removeUndesiredLayers(desiredLayers)
    syncSources()

    val desiredByAnchor = LinkedHashMap<Anchor, MutableList<LayerNode<*>>>()
    desiredLayers.forEach { desiredByAnchor.getOrPut(it.anchor) { mutableListOf() }.add(it) }
    desiredByAnchor.forEach { (anchor, group) -> syncAnchorGroup(anchor, group) }
  }

  private fun removeUndesiredLayers(desiredLayers: List<LayerNode<*>>) {
    val desired = desiredLayers.toHashSet()
    val anchors = appliedLayers.entries.iterator()
    while (anchors.hasNext()) {
      val (anchor, group) = anchors.next()
      group
        .filter { it !in desired }
        .forEach { node ->
          group.remove(node)
          if (anchor is Anchor.Replace && group.isEmpty()) {
            replacedLayers.remove(anchor)?.let { original ->
              logger?.i { "Restoring layer ${anchor.layerId}" }
              binding.addLayerBelow(node.layer.id, original)
            }
          }
          logger?.i { "Removing layer ${node.layer.id}" }
          binding.removeLayer(node.layer)
        }
      if (group.isEmpty()) anchors.remove()
    }
  }

  private fun syncSources() {
    val desired = sourceManager.desiredSources
    desired.forEach { source ->
      if (appliedSources.add(source)) {
        logger?.i { "Adding source ${source.id}" }
        binding.addSource(source)
        sourceManager.state?.refreshSource(source.id)
      }
    }
    appliedSources
      .filter { it !in desired }
      .forEach { source ->
        appliedSources.remove(source)
        logger?.i { "Removing source ${source.id}" }
        binding.removeSource(source)
        sourceManager.state?.refreshSource(source.id)
      }
  }

  private fun syncAnchorGroup(anchor: Anchor, desired: List<LayerNode<*>>) {
    val applied = appliedLayers[anchor] ?: mutableListOf()
    if (applied.isEmpty()) {
      // A style switch can recompose content whose anchors name layers of the incoming base style
      // before this node's style has been swapped; the group waits for a later sync.
      if (!anchor.isResolvable()) {
        logger?.w { "Anchor $anchor names no layer in the current style; deferring its layers" }
        return
      }
      initializeAnchor(anchor, desired)
      appliedLayers[anchor] = desired.toMutableList()
      return
    }

    // Nodes whose applied indices already rise with the desired order stay put; the rest move.
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
          moveLayerAbove(checkNotNull(previousId), layer)
        }
        previousId != null -> {
          logger?.i { "Adding layer ${layer.id} above $previousId" }
          binding.addLayerAbove(checkNotNull(previousId), layer)
        }
        else -> {
          // The first applied node in desired order is always stable, so a head insert finds it.
          val head = desired.drop(position + 1).first { it in stable }
          logger?.i { "Adding layer ${layer.id} below ${head.layer.id}" }
          binding.addLayerBelow(head.layer.id, layer)
        }
      }
      previousId = layer.id
    }
    appliedLayers[anchor] = desired.toMutableList()
  }

  private fun initializeAnchor(anchor: Anchor, desired: List<LayerNode<*>>) {
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
        logger?.i { "Replacing layer ${layerToReplace.id} with ${first.layer.id}" }
        binding.removeLayer(layerToReplace)
        replacedLayers[anchor] = layerToReplace
      }
    }
    var previous = first
    desired.drop(1).forEach { node ->
      logger?.i { "Adding layer ${node.layer.id} above ${previous.layer.id}" }
      binding.addLayerAbove(previous.layer.id, node.layer)
      previous = node
    }
  }

  /** Moves an applied layer to sit directly above the applied layer named [targetId]. */
  private fun moveLayerAbove(targetId: String, layer: Layer) {
    val ids = binding.layerIds() ?: return
    val above = ids.getOrNull(ids.indexOf(targetId) + 1).orEmpty()
    if (above == layer.id) return
    binding.moveLayer(layer.id, above)
  }

  private fun Anchor.isResolvable(): Boolean {
    val layerId =
      when (this) {
        is Anchor.Above -> layerId
        is Anchor.Below -> layerId
        is Anchor.Replace -> layerId
        else -> return true
      }
    return binding.layerIds()?.contains(layerId) ?: false
  }
}
