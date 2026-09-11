package org.maplibre.compose.style

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

internal class StyleNode(
  val style: StyleBinding,
  internal val replaceableSourceIds: Set<String> = emptySet(),
  replaceableLayerIds: Set<String> = emptySet(),
) : MapNode {
  val children = mutableListOf<MapNode>()

  private val baseLayerIds = style.layerIds().toSet() - replaceableLayerIds
  internal val sourceManager = SourceManager(this)
  internal val imageManager = ImageManager(this)
  internal val fontManager = FontManager(this)

  // A nested content scope can recompose without its StyleContent parent. This state invalidates
  // that parent after a structural change so it records the post-observer layer-application effect.
  private var applyGeneration by mutableIntStateOf(0)

  internal val currentApplyGeneration: Int
    get() = applyGeneration

  private var pendingResources = 0

  /** Whether a property or font is still being prepared, so the revision is incomplete. */
  internal val hasPendingResources: Boolean
    get() = pendingResources != 0

  internal fun beginResourcePreparation() {
    pendingResources++
    scheduleApplyChanges()
  }

  internal fun endResourcePreparation() {
    pendingResources--
    scheduleApplyChanges()
  }

  internal fun scheduleApplyChanges() {
    applyGeneration++
  }

  fun insertLayer(index: Int, node: LayerNode<*>) {
    require(node.layer.id !in baseLayerIds) {
      "Layer ID '${node.layer.id}' already exists in base style"
    }
    children.add(index, node)
  }

  internal fun snapshotRevision(animatorDurationScale: Float): DesiredStyleRevision =
    DesiredStyleRevision(
      animatorDurationScale = animatorDurationScale,
      sources = sourceManager.desiredSources.map { it.definition() },
      layers =
        children.map { node ->
          node as LayerNode<*>
          DesiredStyleLayer(
            definition = node.layer.definition(),
            anchor = node.anchor,
            onClick = node.onClick,
            onLongClick = node.onLongClick,
            onDoubleClick = node.onDoubleClick,
            hitPadding = node.hitPadding,
            registration = node,
          )
        },
      images = imageManager.desiredImages,
      fonts = fontManager.desiredFonts,
    )
}
