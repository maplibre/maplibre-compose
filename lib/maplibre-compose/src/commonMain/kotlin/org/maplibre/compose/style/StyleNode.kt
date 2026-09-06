package org.maplibre.compose.style

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

internal class StyleNode(
  var style: StyleBinding,
  internal val replaceableSourceIds: Set<String> = emptySet(),
  replaceableLayerIds: Set<String> = emptySet(),
) : MapNode {
  val children = mutableListOf<MapNode>()

  private val baseLayerIds =
    style.getLayers().mapNotNullTo(mutableSetOf()) {
      it.id.takeUnless(replaceableLayerIds::contains)
    }
  internal val sourceManager = SourceManager(this)
  internal val imageManager = ImageManager(this)

  // A nested content scope can recompose without its StyleContent parent. This state invalidates
  // that parent after a structural change so it records the post-observer layer-application effect.
  private var applyGeneration by mutableIntStateOf(0)

  internal val currentApplyGeneration: Int
    get() = applyGeneration

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
            onContextClick = node.onContextClick,
            onDoubleClick = node.onDoubleClick,
            onTwoFingerClick = node.onTwoFingerClick,
            hitPadding = node.hitPadding,
            registration = node,
            clickSubscription = node.clickSubscription.capture(),
            contextClickSubscription = node.contextClickSubscription.capture(),
            doubleClickSubscription = node.doubleClickSubscription.capture(),
            twoFingerClickSubscription = node.twoFingerClickSubscription.capture(),
          )
        },
      images = imageManager.desiredImages,
    )
}
