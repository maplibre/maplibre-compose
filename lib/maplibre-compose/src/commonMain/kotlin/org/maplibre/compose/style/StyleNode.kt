package org.maplibre.compose.style

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.FeaturesClickHandler

/** One layer's click handlers, captured on the host thread for the UI thread to read. */
internal class ClickRoute(
  val layerId: String,
  val onClick: FeaturesClickHandler?,
  val onLongClick: FeaturesClickHandler?,
)

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
  internal var requestSync: () -> Unit = {}

  /**
   * The live style's layer ids in draw order, backing
   * [StyleLayers.ids][org.maplibre.compose.map.StyleLayers.ids]. Snapshot-backed so a composition
   * that reads the ids recomposes when they change.
   */
  internal var liveLayerIds: List<String> by mutableStateOf(emptyList())
    private set

  /** Republishes [liveLayerIds] from the live style; the map's callbacks call it off this node. */
  internal fun refreshLiveLayerIds() {
    liveLayerIds = binding.layerIds().orEmpty()
  }

  /** The binding the applied snapshot below belongs to; a mismatch with [binding] resets it. */
  private var syncedBinding: StyleBinding? = null

  private var baseLayersFor: StyleBinding? = null
  private var baseLayerIds: Set<String> = emptySet()

  private val appliedSources = mutableSetOf<Source>()
  private val appliedLayers = LinkedHashMap<Anchor, MutableList<LayerNode<*>>>()
  private val replacedLayers = mutableMapOf<Anchor.Replace, Layer>()

  /** Originals whose removal threw after their replacement was added; retried each sync. */
  private val pendingReplaceRemovals = mutableMapOf<Anchor.Replace, Layer>()

  /** The click-routing snapshot, topmost layer first; the UI thread reads only this. */
  @Volatile
  internal var clickRoutes: List<ClickRoute> = emptyList()
    private set

  /** The layer ids the composition owns, published each sync for reads off the host thread. */
  @Volatile
  internal var compositionLayerIds: Set<String> = emptySet()
    private set

  /** The sources the composition owns by id, published each sync for reads off the host thread. */
  @Volatile
  internal var compositionSources: Map<String, Source> = emptyMap()
    private set

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

  /**
   * Diffs the desired state against the applied snapshot and mutates the engine to match. Every
   * bookkeeping update happens only after its engine operation succeeds, so a sync that throws
   * resumes from the true engine state on the next flush.
   */
  internal fun applyChanges() {
    val binding = binding
    if (syncedBinding !== binding) {
      appliedSources.clear()
      appliedLayers.clear()
      replacedLayers.clear()
      pendingReplaceRemovals.clear()
      // The base snapshots must capture the pristine style before this sync mutates it.
      baseLayersFor = null
      baseLayerIds()
      sourceManager.captureBaseSources()
      imageManager.ensureAttached()
      syncedBinding = binding
    }
    publishCompositionOwnership()
    if (!binding.isLoaded) {
      publishLiveLayers()
      return
    }

    val desiredLayers = children.filterIsInstance<LayerNode<*>>()
    retryPendingReplaceRemovals(desiredLayers.mapTo(hashSetOf()) { it.anchor })
    removeUndesiredLayers(desiredLayers)
    syncSources()

    val desiredByAnchor = LinkedHashMap<Anchor, MutableList<LayerNode<*>>>()
    desiredLayers.forEach { desiredByAnchor.getOrPut(it.anchor) { mutableListOf() }.add(it) }
    desiredByAnchor.forEach { (anchor, group) -> syncAnchorGroup(anchor, group) }

    publishLiveLayers()
  }

  /** Rebuilds the ownership snapshots from the desired state that this sync applies. */
  private fun publishCompositionOwnership() {
    compositionLayerIds =
      children.filterIsInstance<LayerNode<*>>().mapTo(hashSetOf()) { it.layer.id }
    compositionSources = sourceManager.desiredSources.associateBy { it.id }
  }

  /**
   * Rebuilds [liveLayerIds] and [clickRoutes] from the live draw order and the composition's
   * handlers. The engine callbacks report only map-driven changes, so this reports composition
   * ones.
   */
  private fun publishLiveLayers() {
    val layerNodes = children.filterIsInstance<LayerNode<*>>().associateBy { it.layer.id }
    refreshLiveLayerIds()
    val ids = liveLayerIds
    clickRoutes =
      ids.asReversed().mapNotNull { id ->
        layerNodes[id]?.let { ClickRoute(id, it.onClick, it.onLongClick) }
      }
  }

  /** Finishes a Replace whose original survived a thrown removal after its replacement landed. */
  private fun retryPendingReplaceRemovals(desiredAnchors: Set<Anchor>) {
    pendingReplaceRemovals.entries.toList().forEach { (anchor, original) ->
      // Nothing wants the replacement any more, so the never-removed original simply stays.
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

  private fun removeUndesiredLayers(desiredLayers: List<LayerNode<*>>) {
    val desired = desiredLayers.toHashSet()
    val anchors = appliedLayers.entries.iterator()
    while (anchors.hasNext()) {
      val (anchor, group) = anchors.next()
      group
        .filter { it !in desired }
        .forEach { node ->
          // Removing the group's last layer restores the replaced original first. An original
          // whose removal is still pending needs no restore: it never left the style.
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

  private fun syncSources() {
    val desired = sourceManager.desiredSources
    // Obsolete sources leave first so a replacement instance may reuse a freed id.
    appliedSources
      .filter { it !in desired }
      .forEach { source ->
        logger?.i { "Removing source ${source.id}" }
        binding.removeSource(source)
        appliedSources.remove(source)
        sourceManager.sources?.refreshSource(source.id)
      }
    desired.forEach { source ->
      if (source !in appliedSources) {
        logger?.i { "Adding source ${source.id}" }
        binding.addSource(source)
        appliedSources.add(source)
        sourceManager.sources?.refreshSource(source.id)
      }
    }
  }

  private fun syncAnchorGroup(anchor: Anchor, desired: List<LayerNode<*>>) {
    val applied = appliedLayers[anchor]
    if (applied.isNullOrEmpty()) {
      // A style switch can recompose content whose anchors name layers of the incoming base style
      // before this node's style has been swapped; the group waits for a later sync.
      if (!anchor.isResolvable()) {
        logger?.w { "Anchor $anchor names no layer in the base style; deferring its layers" }
        return
      }
      initializeAnchor(anchor, desired)
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
          applied.remove(node)
          applied.add(applied.indexOfFirst { it.layer.id == previousId } + 1, node)
        }
        previousId != null -> {
          logger?.i { "Adding layer ${layer.id} above $previousId" }
          binding.addLayerAbove(checkNotNull(previousId), layer)
          applied.add(applied.indexOfFirst { it.layer.id == previousId } + 1, node)
        }
        else -> {
          // The first applied node in desired order is always stable, so a head insert finds it.
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

  private fun initializeAnchor(anchor: Anchor, desired: List<LayerNode<*>>) {
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
        // Recorded before the removal so a thrown removal is retried by the next sync.
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
    // Anchors name base-style layers only, per Anchor's contract.
    return layerId in baseLayerIds()
  }
}
