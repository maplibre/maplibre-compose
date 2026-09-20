package org.maplibre.compose.style

import org.maplibre.compose.sources.Source

/** The committed declarations of one style composition. Engine objects live in the reconciler. */
internal class StyleNode(
  val style: StyleBinding,
  replaceableSourceIds: Set<String> = emptySet(),
  replaceableLayerIds: Set<String> = emptySet(),
  private val publish: (DesiredStyleRevision) -> Unit = {},
) : MapNode {
  val children = mutableListOf<MapNode>()
  private val baseLayerIds = style.layerIds().toSet() - replaceableLayerIds
  private val baseSources =
    style.getSources().filterNot { it.id in replaceableSourceIds }.associateBy { it.id }
  private val sourceIds = IncrementingId("source")
  val images = StyleImageCache()
  private var previous: DesiredStyleRevision? = null
  private var closed = false

  fun nextSourceId(): String = sourceIds.next()

  fun getBaseSource(id: String): Source? = baseSources[id]

  fun commit() {
    if (closed || !style.isLoaded) return
    val revision = snapshotRevision()
    images.retain(children.filterIsInstance<StyleImageNode>())
    if (revision != previous) {
      previous = revision
      publish(revision)
    }
  }

  fun close() {
    closed = true
    images.clear()
  }

  internal fun snapshotRevision(): DesiredStyleRevision {
    val environment = children.filterIsInstance<StyleEnvironmentNode>().singleOrNull()
    val layerNodes = children.filterIsInstance<LayerNode<*>>()
    val sources =
      layerNodes
        .mapNotNull { it.source }
        .distinct()
        .filter { source ->
          val base = baseSources[source.id]
          require(base == null || base === source) {
            "Source ID '${source.id}' conflicts with a base source"
          }
          base == null
        }
    layerNodes.forEach {
      require(it.layer.id !in baseLayerIds) {
        "Layer ID '${it.layer.id}' already exists in base style"
      }
    }
    return DesiredStyleRevision(
      animatorDurationScale = environment?.animatorDurationScale ?: 1f,
      fontScale = environment?.fontScale,
      sources = sources.map { it.definition() },
      layers =
        layerNodes.map { node ->
          DesiredStyleLayer(
            definition = node.layer.definition(),
            anchor = node.anchor,
            onClick = node.onClick,
            onLongClick = node.onLongClick,
            onDoubleClick = node.onDoubleClick,
            hitPadding = node.hitPadding,
            registration = node,
            clickGroup = node.clickGroup,
          )
        },
      images =
        children
          .filterIsInstance<StyleImageNode>()
          .mapNotNull { it.definition }
          .distinctBy { it.id },
      imagesPending = children.filterIsInstance<StyleImageNode>().any { it.definition == null },
    )
  }
}

internal class StyleEnvironmentNode : MapNode {
  var animatorDurationScale: Float = 1f
  var fontScale: Float? = null
}

/** A null definition denotes painter work that has not yet reached a committed property. */
internal class StyleImageNode : MapNode {
  var request: StyleImageCache.Request? = null
  var definition: StyleImageDefinition? = null
}
