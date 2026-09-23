package org.maplibre.compose.style

import org.maplibre.compose.sources.Source

/** The committed declarations of one style composition. Engine objects live in the reconciler. */
internal class StyleNode(
  val style: StyleBinding,
  replaceableSourceIds: Set<String> = emptySet(),
  replaceableLayerIds: Set<String> = emptySet(),
  private val publish: (StyleDeclaration) -> Unit = {},
) : MapNode {
  val children = mutableListOf<MapNode>()
  private val baseLayerIds = style.baseLayerSummaries().keys - replaceableLayerIds
  private val baseSourceIds = style.sourceIds().toSet() - replaceableSourceIds
  /** Base sources read for [getBaseSource], by ID; null for one the engine cannot reconstruct. */
  private val baseSources = mutableMapOf<String, Source?>()
  private val sourceIds = IncrementingId("source")
  private var previous: StyleDeclaration? = null
  private var closed = false

  fun nextSourceId(): String = sourceIds.next()

  fun getBaseSource(id: String): Source? {
    if (id !in baseSourceIds) return null
    if (id !in baseSources) baseSources[id] = style.getSource(id)
    return baseSources[id]
  }

  fun commit() {
    if (closed || !style.isLoaded) return
    val declaration = snapshotDeclaration()
    if (declaration != previous) {
      previous = declaration
      publish(declaration)
    }
  }

  fun close() {
    closed = true
  }

  private fun snapshotDeclaration(): StyleDeclaration {
    val environment = children.filterIsInstance<StyleEnvironmentNode>().singleOrNull()
    val layerNodes = children.filterIsInstance<LayerNode>()
    val sources =
      layerNodes
        .mapNotNull { it.source }
        .distinct()
        .filter { source ->
          val base = source.id in baseSourceIds
          require(!base || baseSources[source.id] === source) {
            "Source ID '${source.id}' conflicts with a base source"
          }
          !base
        }
    layerNodes.forEach {
      require(it.definition.id !in baseLayerIds) {
        "Layer ID '${it.definition.id}' already exists in base style"
      }
    }
    return StyleDeclaration(
      animatorDurationScale = environment?.animatorDurationScale ?: 1f,
      fontScale = environment?.fontScale,
      sources = sources.map { it.definition() },
      layers =
        layerNodes.map { node ->
          DeclaredStyleLayer(
            DesiredStyleLayer(
              definition = node.definition,
              anchor = node.anchor,
              onClick = node.onClick,
              onLongClick = node.onLongClick,
              onDoubleClick = node.onDoubleClick,
              hitPadding = node.hitPadding,
              registration = node.registration,
              clickGroup = node.clickGroup,
            ),
            node.imageProperties,
          )
        },
    )
  }
}

internal class StyleEnvironmentNode : MapNode {
  var animatorDurationScale: Float = 1f
  var fontScale: Float? = null
}
