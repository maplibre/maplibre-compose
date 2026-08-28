package org.maplibre.compose.style

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile
import org.maplibre.compose.sources.Source
import org.maplibre.compose.util.FeaturesClickHandler

/** The pristine base style's layer ids and sources, captured before any sync mutates a binding. */
internal class BaseStyleSnapshot(internal val binding: StyleBinding) {
  internal val layerIds: Set<String> = binding.getLayers().mapTo(mutableSetOf()) { it.id }
  internal val sources: Map<String, Source> = binding.getSources().associateBy { it.id }
}

/** One layer's click handlers, captured on the host thread for the UI thread to read. */
internal class ClickRoute(
  val layerId: String,
  val onClick: FeaturesClickHandler?,
  val onLongClick: FeaturesClickHandler?,
)

/**
 * The root of the style composition. The composition's callbacks record desired state only. A
 * [DesiredStyleRevision] is captured from the tree, and [StyleApplier] applies it to the binding.
 */
internal class StyleNode(binding: StyleBinding, internal var logger: Logger?) : MapNode() {

  /** Snapshot-backed so content that reads it recomposes when the style swaps. */
  internal var binding: StyleBinding by mutableStateOf(binding)

  internal val sourceManager = SourceManager(this)
  internal val imageManager = ImageManager(this)
  private val applier = StyleApplier()

  /** Set by the host; asks it to run a sync when desired state changes outside a frame. */
  internal var requestSync: () -> Unit = {}

  /** Set by the host; publishes a survivable failure to the host's style-error flow. */
  internal var reportError: (StyleError) -> Unit = {}

  /**
   * Set by [MapState][org.maplibre.compose.map.MapState]; the record is the authority for
   * imperative source ids.
   */
  internal var appSourceOwned: (String) -> Boolean = { false }

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

  private var baseStyleSnapshot: BaseStyleSnapshot? = null

  /** The click-routing snapshot, topmost layer first; the UI thread reads only this. */
  @Volatile
  internal var clickRoutes: List<ClickRoute> = emptyList()
    private set

  /** The layer ids the composition owns, published each apply for click routing. */
  @Volatile
  internal var compositionLayerIds: Set<String> = emptySet()
    private set

  /** Empties published composition snapshots; the close and unload paths call it. */
  internal fun clearPublishedOwnership() {
    compositionLayerIds = emptySet()
    liveLayerIds = emptyList()
  }

  override fun allowsChild(node: MapNode) = node is LayerNode<*>

  override fun onChildInserted(index: Int, node: MapNode) {
    node as LayerNode<*>
    require(node.layer.id !in baseStyle().layerIds) {
      "Layer ID '${node.layer.id}' already exists in base style"
    }
    logger?.i { "Recorded layer ${node.layer.id} at anchor ${node.anchor}, index $index" }
  }

  /** Captured lazily because [SourceManager.getBaseSource] runs before the first sync. */
  internal fun baseStyle(): BaseStyleSnapshot {
    val binding = binding
    baseStyleSnapshot?.let { if (it.binding === binding) return it }
    return BaseStyleSnapshot(binding).also { baseStyleSnapshot = it }
  }

  /** Captures the composition tree as an immutable revision. */
  internal fun snapshotRevision(): DesiredStyleRevision =
    DesiredStyleRevision(
      sources = LinkedHashSet(sourceManager.desiredSources),
      layers = children.filterIsInstance<LayerNode<*>>(),
    )

  /**
   * Applies [revision] to the current binding and publishes live layer ids and click routes. Tests
   * and host-less compositions call [applyChanges], which snapshots then applies.
   */
  internal fun applyRevision(revision: DesiredStyleRevision) {
    val binding = binding
    if (baseStyleSnapshot?.binding !== binding) {
      baseStyleSnapshot = null
      baseStyle()
    }
    applier.apply(
      binding = binding,
      revision = revision,
      baseStyle = baseStyle(),
      imageManager = imageManager,
      refreshSource = { sourceManager.sources?.refreshSource(it) },
      reportError = reportError,
      logger = logger,
    )
    compositionLayerIds = revision.layerIds
    publishLiveLayers()
  }

  /**
   * Snapshots the desired tree and applies it. [MapState] commits ownership first, then calls
   * [applyRevision] so a queued apply after close or a style reload cannot republish.
   */
  internal fun applyChanges() {
    applyRevision(snapshotRevision())
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
}
