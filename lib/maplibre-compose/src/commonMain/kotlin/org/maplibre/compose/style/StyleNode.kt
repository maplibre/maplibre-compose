package org.maplibre.compose.style

import kotlinx.coroutines.CoroutineScope
import org.maplibre.compose.map.ResolvedStyleImage
import org.maplibre.compose.sources.Source

/**
 * The committed declarations of one style composition. Engine objects live in the reconciler.
 *
 * A commit or a prepared image only marks the node changed and calls [invalidated]. The consumer
 * builds the revision with [takeRevision] when it can apply one, so changes that arrive while it is
 * busy cost one build instead of one each.
 */
internal class StyleNode(
  val style: StyleBinding,
  imageScope: CoroutineScope,
  replaceableSourceIds: Set<String> = emptySet(),
  replaceableLayerIds: Set<String> = emptySet(),
  prepareImage: suspend (StyleImageRequest) -> ResolvedStyleImage = { it.prepare() },
  private val invalidated: () -> Unit = {},
) : MapNode {
  val children = mutableListOf<MapNode>()
  private val baseLayerIds = style.baseLayers.mapTo(mutableSetOf()) { it.id } - replaceableLayerIds
  private val baseSourceIds = style.baseSources.keys - replaceableSourceIds
  private val sourceIds = IncrementingId("source")
  private val sourceDefinitions = mutableMapOf<Source, SourceDefinition>()
  private var previous: StyleSnapshot? = null
  private var committedLayers = emptyList<LayerNode>()
  private var committedSources = emptyList<SourceDefinition>()
  private var animatorDurationScale = 1f
  private var fontScale: Float? = null
  private val images = StyleImageRegistry(imageScope, prepareImage, ::invalidate)
  private var closed = false
  private var changed = false

  fun nextSourceId(): String = sourceIds.next()

  fun getBaseSource(id: String): Source? = if (id in baseSourceIds) style.baseSources[id] else null

  fun close() {
    closed = true
    images.close()
    sourceDefinitions.clear()
  }

  fun commit() {
    if (closed || !style.isLoaded) return
    val environment = children.filterIsInstance<StyleEnvironmentNode>().singleOrNull()
    val layerNodes = children.filterIsInstance<LayerNode>()
    val sources =
      layerNodes
        .mapNotNull { it.source }
        .distinct()
        .filter { source ->
          val base = source.id in baseSourceIds
          require(!base || style.baseSources[source.id] === source) {
            "Source ID '${source.id}' conflicts with a base source"
          }
          !base
        }
    layerNodes.forEach {
      require(it.definition.id !in baseLayerIds) {
        "Layer ID '${it.definition.id}' already exists in base style"
      }
    }
    sourceDefinitions.keys.retainAll(sources.toSet())
    committedSources = sources.map { sourceDefinitions.getOrPut(it) { it.definition() } }
    committedLayers = layerNodes
    animatorDurationScale = environment?.animatorDurationScale ?: 1f
    fontScale = environment?.fontScale
    images.update(
      layerNodes.flatMap { it.imageProperties.values }.flatMap { it.images }.toSet(),
      previous?.images.orEmpty(),
    )
    invalidate()
  }

  private fun invalidate() {
    if (closed || changed) return
    changed = true
    invalidated()
  }

  /**
   * Builds a revision from the latest commit and prepared images. Returns null if nothing changed
   * since the last revision this returned.
   */
  fun takeRevision(): StyleSnapshot? {
    if (closed || !changed) return null
    changed = false
    if (!style.isLoaded) return null
    val resolved = images.resolved
    val resolvedIds = resolved.mapValues { it.value.id }
    val revision =
      StyleSnapshot(
        sources = committedSources,
        layers = committedLayers.map { it.snapshot(resolved, resolvedIds) },
        images = committedLayers.flatMap { it.images }.distinctBy { it.id },
        animatorDurationScale = animatorDurationScale,
        fontScale = fontScale,
        imagesPending = images.pending,
      )
    images.retain(revision.images)
    if (revision == previous) return null
    previous = revision
    return revision
  }
}

internal class StyleEnvironmentNode : MapNode {
  var animatorDurationScale: Float = 1f
  var fontScale: Float? = null
}
